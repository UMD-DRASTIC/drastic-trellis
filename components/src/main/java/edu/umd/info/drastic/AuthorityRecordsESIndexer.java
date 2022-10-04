package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment.Strategy;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
public class AuthorityRecordsESIndexer {
	private static final Logger LOGGER = getLogger(AuthorityRecordsESIndexer.class);

    @Inject
    @ConfigProperty(name = "trellis.triplestore-query-url", defaultValue = "http://localhost:3030/ds/query")
    URI triplestoreQueryUrl;
    
    @Inject
    @ConfigProperty(name = "trellis.elasticsearch-url", defaultValue = "http://localhost:9200/")
    URI elasticSearchUrl;
    
    final JsonNodeFactory factory = JsonNodeFactory.instance;
    
	@Incoming("elasticsearch-authrec-in")
	@Blocking("elasticsearch-suppliers")
	@Acknowledgment(Strategy.PRE_PROCESSING)
	public void run(String msg) {
		if(!msg.contains("/name-authority/")) return;
		LOGGER.info("Reindexing name authorities in Elasticsearch: {}", msg);
		// SPARQL query to get all names, alt names, and their identifiers.
		JsonNode results = getAuthorityRecords();
		if(!results.has("results")) {
			LOGGER.warn("got no json results for subjects:\n{}", results);
		}
		String es_bulk = StreamSupport.stream(results.at("/results/bindings").spliterator(), false)
			.collect(Collectors.groupingBy(r -> r.get("id").get("value").asText()))
			.entrySet().parallelStream()
			.<List<ObjectNode>>map(s -> {
				String id = s.getKey();
				ObjectNode action = factory.objectNode();
				action.set("index", factory.objectNode()
						.<ObjectNode>set("_id", factory.textNode(id)).set("_index", factory.textNode("authority-records")));
				ObjectNode source = factory.objectNode();
				source.set("id", factory.textNode(id));
				ArrayNode labels = factory.arrayNode();
				s.getValue().forEach( l -> {
					if(l.has("pref")) {
						source.set("prefLabel", factory.textNode(l.get("pref").get("value").asText()));
						labels.add(l.get("pref").get("value").asText());
					} else {
						labels.add(l.get("label").get("value").asText());
					}
				});
				source.set("labels", labels);
				return Arrays.asList(action, source);
			})
			.flatMap(list -> list.stream()).<StringBuilder>collect(StringBuilder::new, 
					(b, x) -> {	b.append(x); b.append("\n"); },
					(a, b) -> {	a.append(b.toString()); }).toString();
		postElasticDocument(es_bulk);
	}

	private JsonNode getAuthorityRecords() {
		String query = "select ?id ?pref ?label WHERE { GRAPH ?g { " +
				"?id a <http://www.w3.org/2004/02/skos/core#Concept> . " +
				"{ ?id <http://www.w3.org/2004/02/skos/core#prefLabel> ?pref . } " +
				"UNION " +
				"{ ?id <http://www.w3.org/2008/05/skos-xl#altLabel> ?alt . " + 
				"?alt <http://www.w3.org/2008/05/skos-xl#literalForm> ?label . } " +
				"} }";
		try {
			HttpClient http = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(triplestoreQueryUrl).POST(BodyPublishers.ofString(query))
				.header("Accept", "application/json")
				.header("Content-Type", "application/sparql-query; charset=utf-8")
				.build();
			String body = http.send(req, BodyHandlers.ofString()).body();
			return new ObjectMapper().readTree(body);
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Cannot get SPARQL query response for authority records.", e);
			throw new CompletionException(e);
		}
	}
	
	private String postElasticDocument(String body) {
		LOGGER.debug("Bulk ES post: \n{}", body);
	    URI uri = URI.create(this.elasticSearchUrl + "/_bulk");
		try {
			HttpClient http = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(uri).POST(BodyPublishers.ofString(body))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json; charset=utf-8")
				.build();
			return http.send(req, BodyHandlers.ofString()).body();
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Cannot POST ES bulk doc\n{}", body, e);
			throw new CompletionException(e);
		}
	}
}
