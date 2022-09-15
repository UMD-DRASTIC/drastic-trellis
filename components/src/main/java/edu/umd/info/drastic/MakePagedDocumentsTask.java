package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.localhost;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;

import org.apache.commons.rdf.api.BlankNode;
import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.api.RDFFactory;
import org.trellisldp.vocabulary.Trellis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import edu.umd.info.drastic.NPSVocabulary.IANA;
import edu.umd.info.drastic.NPSVocabulary.NPS;
import edu.umd.info.drastic.NPSVocabulary.ORE;
import edu.umd.info.drastic.NPSVocabulary.PCDM;

public class MakePagedDocumentsTask {
	private static final Logger LOGGER = getLogger(MakePagedDocumentsTask.class);

	@Inject
	@ConfigProperty(name = "trellis.triplestore-query-url", defaultValue = "http://localhost:3030/ds/query")
	URI triplestoreQueryUrl;

	private final RDF rdf = RDFFactory.getInstance();

	private ExecutorService executorService = Executors.newFixedThreadPool(1);

	@Incoming("makePagedDocuments")
	public void makePagedDocuments(final String submissionUri) {
		LOGGER.debug("make paged docs task: {}", submissionUri);
		CompletableFuture.supplyAsync(() -> getPageFiles(submissionUri), executorService).thenAccept(lists -> {
			String lastDocId = null;
			int lastPageNo = 1;
			List<String> pageFiles = lists.get("pageFiles");
			LOGGER.debug("Got page files: {}", pageFiles);
			List<String> docPages = new ArrayList<String>();
			for (String f : pageFiles) {
				String docid = NPSFilenameUtil.getPageDocumentID(f);
				if (lastDocId == null)
					lastDocId = docid;
				if (!lastDocId.equals(docid)) {
					Map<String, List<String>> doclists = new HashMap<String, List<String>>();
					doclists.put("pageAccessFiles", lists.get("pageAccessFiles"));
					doclists.put("pageThumbnailFiles", lists.get("pageThumbnailFiles"));
					doclists.put("pageFiles", docPages);
					makeDoc(submissionUri, lastDocId, doclists);
					docPages.clear();
					lastDocId = docid;
				}
				int pageNo = NPSFilenameUtil.getPageNumber(f);
				for (int i = lastPageNo; i + 1 < pageNo; i++) {
					docPages.add(null); // add a missing page
				}
				lastPageNo = pageNo;
				docPages.add(f);
			}
			if (docPages.size() > 0) { // process last document
				Map<String, List<String>> doclists = new HashMap<String, List<String>>();
				doclists.put("pageAccessFiles", lists.get("pageAccessFiles"));
				doclists.put("pageThumbnailFiles", lists.get("pageThumbnailFiles"));
				doclists.put("pageFiles", docPages);
				makeDoc(submissionUri, lastDocId, doclists);
			}
		});
	}

	private void makeDoc(String submissionUri, String docId, Map<String, List<String>> mixedPageFiles) {
		try {
		List<String> pageFiles = mixedPageFiles.get("pageFiles");
		List<String> pageAccessFiles = mixedPageFiles.get("pageAccessFiles");
		List<String> pageThumbnailFiles = mixedPageFiles.get("pageThumbnailFiles");
		IRI doc = rdf.createIRI(submissionUri + docId);
		Dataset d = rdf.createDataset();
		Graph g = d.getGraph(Trellis.PreferUserManaged).get();
		g.add(doc, org.trellisldp.vocabulary.RDF.type, PCDM.Object.iri);

		// establish the collection path of the main document
		String docPath = NPSFilenameUtil.getDocumentPath(docId);
		g.add(doc, NPSVocabulary.NPS.path.iri, rdf.createLiteral("/" + docPath));

		String folderPath = NPSFilenameUtil.getFolderPath(docId);
		URL folder;
		try {
			folder = new URL(new URL(doc.getIRIString()), "/description/" + folderPath);
		} catch (MalformedURLException e1) {
			LOGGER.error("Failed to build folder description url", e1);
			return;
		}
		g.add(rdf.createIRI(folder.toExternalForm()), PCDM.hasMember.iri, doc);
		List<BlankNode> proxyOrder = new ArrayList<BlankNode>();
		for (int i = 0; i < pageFiles.size(); i++) {
			IRI pageFile = null;
			if (pageFiles.get(i) == null) {
				pageFile = NPSVocabulary.NPS.MissingPageFile.iri;
			} else {
				pageFile = rdf.createIRI(pageFiles.get(i));
			}
			BlankNode page = rdf.createBlankNode();
			g.add(doc, PCDM.hasMember.iri, page);
			g.add(page, org.trellisldp.vocabulary.RDF.type, PCDM.Object.iri);
			g.add(page, PCDM.hasFile.iri, pageFile);
			try {
				String accessFileUrl = NPSFilenameUtil.getAccessImageURL(pageFile.getIRIString());
				if (pageAccessFiles.contains(accessFileUrl)) {
					LOGGER.debug("found access file: {}", accessFileUrl);
					g.add(page, NPS.hasAccess.iri, rdf.createIRI(accessFileUrl));
				}
				String thumbFileUrl = NPSFilenameUtil.getThumbnailImageURL(pageFile.getIRIString());
				LOGGER.debug(thumbFileUrl);
				if (pageThumbnailFiles.contains(thumbFileUrl)) {
					LOGGER.debug("found thumbnail file: {}", thumbFileUrl);
					g.add(page, NPS.hasThumbnail.iri, rdf.createIRI(thumbFileUrl));
				}
			} catch (IllegalArgumentException e) {
				LOGGER.error("Unexpected argument exception", e);
			}
			BlankNode proxy = rdf.createBlankNode();
			proxyOrder.add(proxy);
			g.add(proxy, org.trellisldp.vocabulary.RDF.type, ORE.Proxy.iri);
			g.add(proxy, ORE.proxyIn.iri, doc);
			g.add(proxy, ORE.proxyFor.iri, page);
			if (i == 0) {
				g.add(doc, IANA.first.iri, proxy);
			}
			if (i == pageFiles.size() - 1) {
				g.add(doc, IANA.last.iri, proxy);
			}
		}
		for (int i = 0; i < proxyOrder.size(); i++) {
			try {
				g.add(proxyOrder.get(i), IANA.prev.iri, proxyOrder.get(i - 1));
			} catch (IndexOutOfBoundsException ignored) {
			}
			try {
				g.add(proxyOrder.get(i), IANA.next.iri, proxyOrder.get(i + 1));
			} catch (IndexOutOfBoundsException ignored) {
			}
		}
		String body = g.toString();
		LOGGER.debug("creating doc: {}", body);
		HttpClient http = HttpClient.newHttpClient();

			http.send(HttpRequest.newBuilder(localhost(doc.getIRIString())).method("PUT", BodyPublishers.ofString(body))
					.header("Link", "<http://www.w3.org/ns/ldp#RDFSource>; rel=\"type\"")
					.header("Content-Type", "text/turtle").build(), BodyHandlers.discarding());
		} catch (Exception e) {
			LOGGER.error("Failed to put turtle for paged document", e);
			return;
		}
	}

	private Map<String, List<String>> getPageFiles(String submissionUri) {
		String q = "select ?o FROM <" + NPS.containsGraph.str + "> WHERE { <" + submissionUri
				+ "> <http://www.w3.org/ns/ldp#contains>*/<http://www.w3.org/ns/ldp#contains> ?o. }";
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(triplestoreQueryUrl).method("POST", BodyPublishers.ofString(q))
				.header("Content-Type", "application/sparql-query; charset=utf-8").header("Accept", "application/json")
				.build();
		HttpResponse<String> res;
		try {
			res = http.send(req, BodyHandlers.ofString());
		} catch (IOException | InterruptedException e1) {
			LOGGER.error("Cannot fetch submission contents", e1);
			return null;
		}
		JsonNode as;
		try {
			as = new ObjectMapper().readTree(res.body());
		} catch (JsonProcessingException e) {
			LOGGER.error("cannot parse activitystream", e);
			return null;
		}
		List<String> pageFiles = StreamSupport
				.stream(((ArrayNode) as.get("results").get("bindings")).spliterator(), false).map(n -> {
					return n.get("o").get("value").asText();
				}).filter(NPSFilenameUtil.PAGE_FILE_PREDICATE).sorted().collect(Collectors.toList());
		List<String> pageAccessFiles = StreamSupport
				.stream(((ArrayNode) as.get("results").get("bindings")).spliterator(), false).map(n -> {
					return n.get("o").get("value").asText();
				}).filter(NPSFilenameUtil.PAGE_ACCESS_FILE_PREDICATE).sorted().collect(Collectors.toList());
		List<String> pageThumbnailFiles = StreamSupport
				.stream(((ArrayNode) as.get("results").get("bindings")).spliterator(), false).map(n -> {
					return n.get("o").get("value").asText();
				}).filter(NPSFilenameUtil.PAGE_THUMBNAIL_FILE_PREDICATE).sorted().collect(Collectors.toList());
		Map<String, List<String>> result = new HashMap<String, List<String>>();
		result.put("pageFiles", pageFiles);
		result.put("pageAccessFiles", pageAccessFiles);
		result.put("pageThumbnailFiles", pageThumbnailFiles);
		return result;
	}

//	public static String encode(final String input, final String encoding) {
//        if (input != null) {
//            try {
//                return URLEncoder.encode(input, encoding);
//            } catch (final UnsupportedEncodingException ex) {
//                throw new UncheckedIOException("Invalid encoding: " + encoding, ex);
//            }
//        }
//        return "";
//    }

//    public static String sparqlQuery(final String command) {
//    	return "query=" + encode(command, "UTF-8");
//    }
}
