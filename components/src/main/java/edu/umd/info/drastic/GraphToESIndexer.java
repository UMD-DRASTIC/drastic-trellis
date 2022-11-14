package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment.Strategy;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.vocabulary.RDF;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.umd.info.drastic.NPSVocabulary.ICMS;
import edu.umd.info.drastic.NPSVocabulary.NPS;
import io.smallrye.reactive.messaging.annotations.Blocking;

/**
 * The GraphToESIndexer is responsible for indexing things in Elasticsearch
 * whenever graphs are updated or added to the triple store. Since the triples
 * for any subject may be spread over many named graphs in LDP, the indexer
 * first gathers the list of relevant subjects from the updated graph, then
 * builds the index document from statements about those subjects from any named graph.
 * 
 * @author jansen
 *
 */
public class GraphToESIndexer {
	private static final Logger LOGGER = getLogger(GraphToESIndexer.class);

    @Inject
    @ConfigProperty(name = "trellis.triplestore-query-url", defaultValue = "http://localhost:3030/ds/query")
    URI triplestoreQueryUrl;
    
    @Inject
    @ConfigProperty(name = "trellis.elasticsearch-index-url", defaultValue = "http://localhost:9200/descriptions")
    URI elasticSearchIndexUrl;
    
	public static final Set<String> excludedFulltextPredicates = new HashSet<String>();
    static {
		excludedFulltextPredicates.add(ICMS.Notes.str);
		excludedFulltextPredicates.add(ICMS.Location.str);
		excludedFulltextPredicates.add(ICMS.Addl_x0020_Acc_x0023_.str);
    }
    
    public static final Set<String> skipPaths = new HashSet<String>();
    static {
    	skipPaths.add("/description");
    	skipPaths.add("/description/");
    	skipPaths.add("/submissions");
    	skipPaths.add("/submissions/");
    	skipPaths.add("/");
    	skipPaths.add("");
    }
    
	@Incoming("elasticsearch-graph-in")
	@Blocking("elasticsearch-suppliers")
	@Acknowledgment(Strategy.PRE_PROCESSING)
	public void processNewGraph(String msg) {
		LOGGER.info("processing graph to ES: {}", msg);
		//if(msg.contains("BX")) LOGGER.debug("processing box graph uri: {}", msg);
		URI graphUri = URI.create(msg);
		if(skipPaths.contains(graphUri.getPath())) {
			//LOGGER.debug("skipping path: {}", graphUri.getPath());
			return;
		}
		getSubjectsInGraph(graphUri).forEach(s -> processSubject(s));
	}

	private void processSubject(URI subjectURI) {
		if(subjectURI.toASCIIString().contains("BX")) LOGGER.debug("processing box subject uri: {}", subjectURI.toASCIIString());
		JsonNode stmts = getSubject(subjectURI);
		boolean pcdmObject = StreamSupport.stream(
				stmts.get("results").get("bindings").spliterator(), true)
			.anyMatch(n -> {
				return RDF.type.getIRIString().equals(n.get("p").get("value").asText()) &&
				NPSVocabulary.PCDM.Object.str.equals(n.get("o").get("value").asText());
			});
		boolean pcdmCollection = StreamSupport.stream(
				stmts.get("results").get("bindings").spliterator(), true)
			.anyMatch(n -> {
				return RDF.type.getIRIString().equals(n.get("p").get("value").asText()) &&
				NPSVocabulary.PCDM.Collection.str.equals(n.get("o").get("value").asText());
			});
		if(pcdmObject || pcdmCollection) {
			if(subjectURI.toASCIIString().contains("BX")) LOGGER.debug("BOX PCDM_Object or PCDM_Collection detected, indexing DCE statements: {}", subjectURI);
			
			// Copy DCTERMS statements
			ObjectMapper m = new ObjectMapper();
			ObjectNode es_doc = m.createObjectNode();
			es_doc.put("uri", subjectURI.toASCIIString());
			StreamSupport.stream(
				stmts.at("/results/bindings").spliterator(), true)
				.filter(n -> { return n.get("p").get("value").asText().startsWith(NPSVocabulary.DCTERMS_NS.getIRIString());})
				.forEach(n -> {
					String key = n.get("p").get("value").asText().substring(NPSVocabulary.DCTERMS_NS.getIRIString().length());
					if(!es_doc.has(key)) {
						es_doc.putArray(key);
					}
					ArrayNode an = (ArrayNode)es_doc.get(key);
					an.add(n.get("o").get("value").asText());
				});
			
			// Create fulltext, path, and pathfacet
			if(pcdmCollection) {
				String fulltext = StreamSupport.stream(stmts.at("/results/bindings").spliterator(), true)
				.filter(n -> {
					String pred = n.get("p").get("value").asText();
					return pred.startsWith(NPSVocabulary.ICMS_NS.getIRIString()) &&
							!excludedFulltextPredicates.contains(pred);
				}).map(n -> { return n.get("o").get("value"); }).map(JsonNode::asText).collect(Collectors.joining(" "));
				es_doc.put("fulltext", fulltext);
				
				// Add the path stuff to es doc
				String icmsid = StreamSupport.stream(stmts.at("/results/bindings").spliterator(), true)
					.filter(n -> {
						String pred = n.get("p").get("value").asText();
						return ICMS.id.str.equals(pred);
					}).findFirst().map(n -> { return n.get("o").get("value"); }).map(JsonNode::asText).get();
				es_doc.put("pathfacet", icmsid);
				es_doc.put("path", icmsid);
				es_doc.put("depth", icmsid.split("/").length-2);
			} else if(pcdmObject) {
				// TODO find and index thumbnail image
				String thumbnail = getThumbnail(subjectURI);
				es_doc.put("thumbnail", thumbnail);
				// TODO fulltext extraction and aggregation
				
				// add the path fields
				String path = StreamSupport.stream(stmts.at("/results/bindings").spliterator(), true)
						.filter(n -> {
							String pred = n.get("p").get("value").asText();
							return NPS.path.str.equals(pred);
						}).findFirst().map(n -> { return n.get("o").get("value"); }).map(JsonNode::asText).orElse(null);
				if(path != null) {
					es_doc.put("pathfacet", path);
					es_doc.put("path", path);
					es_doc.put("depth", path.split("/").length-2);
				} else {
					LOGGER.warn("Found PCDM_Object without path: {}", subjectURI);
				}
			}
			
			//LOGGER.debug("Elasticsearch doc: {}", es_doc.toPrettyString());
			postElasticDocument(subjectURI, es_doc);
		}
	}
	
	private String getThumbnail(URI subjectURI) {
		String query = "select ?t WHERE { GRAPH ?g { "+ 
		" <"+subjectURI+"> <http://www.iana.org/assignments/relation/first> ?order . "+
		" ?order <http://www.openarchives.org/ore/terms/proxyFor> ?page . "+
		" ?page <https://example.nps.gov/2021/nps-workflow#hasThumbnail> ?t . } } "; 
		try {
			HttpClient http = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(triplestoreQueryUrl).POST(BodyPublishers.ofString(query))
				.header("Accept", "application/json")
				.header("Content-Type", "application/sparql-query; charset=utf-8")
				.build();
			String result = http.send(req, BodyHandlers.ofString()).body();
			//LOGGER.debug("processing this graph:\n{}", result);
			JsonNode as = new ObjectMapper().readTree(result);
			if(!as.has("results")) {
				LOGGER.warn("got no json results for thumbnails:\n{}", result);
			}
			return StreamSupport.stream(as.at("/results/bindings").findValues("t").spliterator(), true)
				.map(s -> { return s.get("value").asText(); } ).findFirst().get();
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Cannot get triples for {}", subjectURI, e);
			throw new CompletionException("Cannot get triples", e);
		}
	}

	private Stream<URI> getSubjectsInGraph(URI iri) {
		try {
			HttpClient http = HttpClient.newHttpClient();
			String query = "select DISTINCT ?s FROM <"+iri.toASCIIString()+"> WHERE { ?s ?p ?o. }";
			HttpRequest req = HttpRequest.newBuilder(triplestoreQueryUrl).POST(BodyPublishers.ofString(query))
				.header("Accept", "application/json")
				.header("Content-Type", "application/sparql-query; charset=utf-8")
				.build();
			String result = http.send(req, BodyHandlers.ofString()).body();
			LOGGER.debug("processing this graph:\n{}", result);
			JsonNode as = new ObjectMapper().readTree(result);
			if(!as.has("results")) {
				LOGGER.warn("got no json results for subjects:\n{}", result);
			}
			return StreamSupport.stream(as.at("/results/bindings").findValues("s").spliterator(), true)
				.filter(s -> { return "uri".equals(s.get("type").asText()); } )
				.filter(s  -> { return s.get("value").asText().startsWith("http"); } )
				.map(s -> {	return URI.create(s.get("value").asText()); });
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Cannot get triples for {}", iri, e);
			throw new CompletionException("Cannot get triples", e);
		}
	}
	
	private JsonNode getSubject(URI iri) {
		try {
			HttpClient http = HttpClient.newHttpClient();
			String query = "select ?p ?o WHERE { GRAPH ?g { <"+iri.toASCIIString()+"> ?p ?o. } }";
			HttpRequest req = HttpRequest.newBuilder(triplestoreQueryUrl).POST(BodyPublishers.ofString(query))
				.header("Accept", "application/json")
				.header("Content-Type", "application/sparql-query; charset=utf-8")
				.build();
			String body = http.send(req, BodyHandlers.ofString()).body();
			//LOGGER.debug("Got subject statements for {}: {}", iri, body);
			return new ObjectMapper().readTree(body);
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Cannot get triples for {}", iri, e);
			throw new CompletionException(e);
		}
	}
	
	private String postElasticDocument(URI iri, ObjectNode es_doc) {
		String iriPath = iri.getPath();
	    URI uri = URI.create(this.elasticSearchIndexUrl + "/_doc/" + iriPath.replace('/', '-'));
	    //"level": x['icms:level'],
	    // "depth": len(x['icms:id'].split('/'))-2,
		try {
			HttpClient http = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(uri).POST(BodyPublishers.ofString(es_doc.toString()))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json; charset=utf-8")
				.build();
			return http.send(req, BodyHandlers.ofString()).body();
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Cannot POST ES doc for {}", iri, e);
			throw new CompletionException(e);
		}
	}
}
