package edu.umd.info.drastic;

import static edu.umd.info.drastic.NPSVocabulary.IANA_first;
import static edu.umd.info.drastic.NPSVocabulary.IANA_last;
import static edu.umd.info.drastic.NPSVocabulary.IANA_next;
import static edu.umd.info.drastic.NPSVocabulary.IANA_prev;
import static edu.umd.info.drastic.NPSVocabulary.NPS_containsGraph;
import static edu.umd.info.drastic.NPSVocabulary.ORE_Proxy;
import static edu.umd.info.drastic.NPSVocabulary.ORE_proxyFor;
import static edu.umd.info.drastic.NPSVocabulary.ORE_proxyIn;
import static edu.umd.info.drastic.NPSVocabulary.PCDM_Object;
import static edu.umd.info.drastic.NPSVocabulary.PCDM_hasFile;
import static edu.umd.info.drastic.NPSVocabulary.PCDM_hasMember;
import static edu.umd.info.drastic.NPSVocabulary.RDF_type;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

public class MakePagedDocumentsTask {
	private static final Logger LOGGER = getLogger(MakePagedDocumentsTask.class);
	
    @Inject
    @ConfigProperty(name = "trellis.triplestore-url", defaultValue = "http://localhost:3030/ds/query")
    URI triplestoreQueryUrl;
    
    private final RDF rdf = RDFFactory.getInstance();

	@Incoming("makePagedDocuments")
	public void makePagedDocuments(final String submissionUri) {
		
		getPageFiles(submissionUri).thenAccept(lists -> {
			String lastDocId = null;
			int lastPageNo = 1;
			List<String> pageFiles = lists.get("pageFiles");
			List<String> docPages = new ArrayList<String>();
			for(String f : pageFiles) {
				String docid = NPSFilenameUtil.getPageDocumentID(f);
				if(lastDocId == null) lastDocId = docid;
				if(!lastDocId.equals(docid)) {
					Map<String, List<String>> doclists = new HashMap<String, List<String>>();
					doclists.put("pageAccessFiles", lists.get("pageAccessFiles"));
					doclists.put("pageFiles", docPages);
					makeDoc(submissionUri, lastDocId, doclists);
					docPages.clear();
					lastDocId = docid;
				}
				int pageNo = NPSFilenameUtil.getPageNumber(f);
				for(int i = lastPageNo; i+1 < pageNo; i++ ) {
					docPages.add(null); // add a missing page
				}
				lastPageNo = pageNo;
				docPages.add(f);
			}
			if(docPages.size() > 0) {  // process last document
				Map<String, List<String>> doclists = new HashMap<String, List<String>>();
				doclists.put("pageAccessFiles", lists.get("pageAccessFiles"));
				doclists.put("pageFiles", docPages);
				makeDoc(submissionUri, lastDocId, doclists);
			}
		});
	}
	
	private void makeDoc(String submissionUri, String docId, Map<String, List<String>> mixedPageFiles) {
		List<String> pageFiles = mixedPageFiles.get("pageFiles");
		List<String> pageAccessFiles = mixedPageFiles.get("pageAccessFiles");
		IRI doc = rdf.createIRI(submissionUri + docId);
		Dataset d = rdf.createDataset();
        Graph g = d.getGraph(Trellis.PreferUserManaged).get();
        g.add(doc, RDF_type, PCDM_Object);
        String folderPath = NPSFilenameUtil.getFolderPath(docId);
        URL folder;
		try {
			folder = new URL(new URL(doc.getIRIString()), "/description/"+folderPath);
		} catch (MalformedURLException e1) {
			LOGGER.debug("Unexpected", e1);
			throw new Error("Unexpected", e1);
		}
        g.add(rdf.createIRI(folder.toExternalForm()), PCDM_hasMember, doc);
        List<BlankNode> proxyOrder = new ArrayList<BlankNode>();
        for(int i = 0; i < pageFiles.size(); i++) {
        	IRI pageFile = null;
        	if(pageFiles.get(i) == null) {
        		pageFile = NPSVocabulary.NPS_MissingPageFile;
        	} else {
        		pageFile = rdf.createIRI(pageFiles.get(i));
        	}
        	BlankNode page = rdf.createBlankNode();
        	g.add(doc, PCDM_hasMember, page);
        	g.add(page, RDF_type, PCDM_Object);
        	g.add(page, PCDM_hasFile, pageFile);
        	String accessFileUrl = NPSFilenameUtil.getAccessImageURL(pageFile.getIRIString());
        	if(pageAccessFiles.contains(accessFileUrl)) {
        		g.add(page, PCDM_hasFile, rdf.createIRI(accessFileUrl));
        	}
        	BlankNode proxy = rdf.createBlankNode();
        	proxyOrder.add(proxy);
	        g.add(proxy, RDF_type, ORE_Proxy);
	        g.add(proxy, ORE_proxyIn, doc);
	        g.add(proxy, ORE_proxyFor, page);
	        if(i == 0) {
	        	g.add(doc, IANA_first, proxy);
	        }
	        if(i == pageFiles.size() -1) {
	        	g.add(doc, IANA_last, proxy);
	        }
        }
        for(int i = 0; i < proxyOrder.size(); i++) {
        	try {
        		g.add(proxyOrder.get(i), IANA_prev, proxyOrder.get(i-1));
        	} catch(IndexOutOfBoundsException ignored) {}
        	try {
        		g.add(proxyOrder.get(i), IANA_next, proxyOrder.get(i+1));
        	} catch(IndexOutOfBoundsException ignored) {}
        }
        String body = g.toString(); 
        HttpClient http = HttpClient.newHttpClient();
        try {
        	http.send(HttpRequest.newBuilder(new URI(doc.getIRIString()))
				.method("PUT", BodyPublishers.ofString(body))
				.header("Link", "<http://www.w3.org/ns/ldp#RDFSource>; rel=\"type\"")
				.header("Content-Type", "text/turtle")
				.build(), BodyHandlers.discarding());
        } catch(Exception e) {
        	throw new Error("unexpected", e);
        }
	}

	private CompletableFuture<Map<String, List<String>>> getPageFiles(String submissionUri) {
		String q = "select ?o FROM <"+ NPS_containsGraph.getIRIString() +"> WHERE { <"+submissionUri
				+"> <http://www.w3.org/ns/ldp#contains>*/<http://www.w3.org/ns/ldp#contains> ?o. }";
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(triplestoreQueryUrl).method("POST", BodyPublishers.ofString(sparqlQuery(q)))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .header("Accept", "application/json")
		        .build();
		return http.sendAsync(req, BodyHandlers.ofString()).thenApply(res -> {
			JsonNode as;
			try {
				as = new ObjectMapper().readTree(res.body());
			} catch (JsonProcessingException e) {
				LOGGER.error("cannot parse activitystream", e);
				return null;
			}
			List<String> pageFiles = StreamSupport.stream(((ArrayNode)as.get("results").get("bindings")).spliterator(), false)
				.map(n -> { return n.get("o").get("value").asText(); })
				.filter(NPSFilenameUtil.PAGE_FILE_PREDICATE)
				.sorted()
				.collect(Collectors.toList());
			List<String> pageAccessFiles = StreamSupport.stream(((ArrayNode)as.get("results").get("bindings")).spliterator(), false)
					.map(n -> { return n.get("o").get("value").asText(); })
					.filter(NPSFilenameUtil.PAGE_ACCESS_FILE_PREDICATE)
					.sorted()
					.collect(Collectors.toList());
			Map<String, List<String>> result = new HashMap<String, List<String>>();
			result.put("pageFiles", pageFiles);
			result.put("pageAccessFiles", pageAccessFiles);
			return result;
		});
	}
	
	public static String encode(final String input, final String encoding) {
        if (input != null) {
            try {
                return URLEncoder.encode(input, encoding);
            } catch (final UnsupportedEncodingException ex) {
                throw new UncheckedIOException("Invalid encoding: " + encoding, ex);
            }
        }
        return "";
    }
	
    public static String sparqlQuery(final String command) {
    	return "query=" + encode(command, "UTF-8");
    }
}
