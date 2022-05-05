package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CompletionException;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment.Strategy;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;

/**
 * The GraphToESIndexer is responsible for indexing things in Elasticsearch
 * whenever graphs are updated or added to the triple store. Since the triples
 * for any subject may be spread of many named graphs in LDP, the indexer
 * first gathers the list of relevant subjects from the updated graph, then
 * builds the index document from statements about the subjects from any graph.
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
    @ConfigProperty(name = "trellis.elasticsearch-url", defaultValue = "http://localhost:9200/descriptions")
    URI elasticSearchUrl;
    
	@Incoming("elasticsearch-graph-in")
	@Acknowledgment(Strategy.PRE_PROCESSING)
	public Uni<Void> processNewGraph(String msg) {
		LOGGER.debug("process new graph uri: {}", msg);
		return Uni.createFrom().item(msg).onItem().transformToUni(graphUri -> {
			String result = getSubjectsInGraph(graphUri);
			LOGGER.debug("processing this graph:\n{}", result);
			try {
				JsonNode as = new ObjectMapper().readTree(result);
				if(as.has("results")) {
					for(JsonNode s : as.at("/results/bindings").findValues("s")) {
						if("uri".equals(s.get("type").asText())) {
							String uri = s.get("value").asText();
							String subjectStmts = getSubject(uri);
							LOGGER.debug("Got subject statements: {}", subjectStmts);
						}
					}
					return Uni.createFrom().voidItem();
				} else {
					LOGGER.debug("got json results:\n{}", as.toPrettyString());
					return Uni.createFrom().voidItem();
				}
			} catch (JsonProcessingException e) {
				LOGGER.error("cannot parse activitystream", e);
				throw new CompletionException("cannot parse activity stream", e);
			}
		});
	}
	
	private String getSubjectsInGraph(String iri) {
		try {
			HttpClient http = HttpClient.newHttpClient();
			String query = "select DISTINCT ?s FROM <"+iri+"> WHERE { ?s ?p ?o. }";
			HttpRequest req = HttpRequest.newBuilder(triplestoreQueryUrl).POST(BodyPublishers.ofString(query))
				.header("Accept", "application/json")
				.header("Content-Type", "application/sparql-query; charset=utf-8")
				.build();
			return http.send(req, BodyHandlers.ofString()).body();
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Cannot get triples for {}", iri, e);
			throw new CompletionException("Cannot get triples", e);
		}
	}
	
	private String getSubject(String iri) {
		try {
			HttpClient http = HttpClient.newHttpClient();
			String query = "select ?p ?o WHERE { GRAPH ?g { <"+iri+"> ?p ?o. } }";
			HttpRequest req = HttpRequest.newBuilder(triplestoreQueryUrl).POST(BodyPublishers.ofString(query))
				.header("Accept", "application/json")
				.header("Content-Type", "application/sparql-query; charset=utf-8")
				.build();
			return http.send(req, BodyHandlers.ofString()).body();
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Cannot get triples for {}", iri, e);
			throw new CompletionException(e);
		}
	}
}
