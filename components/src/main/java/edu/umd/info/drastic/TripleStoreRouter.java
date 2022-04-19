package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.localhost;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;

import org.apache.jena.atlas.lib.SinkToCollection;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDFLib;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.vocabulary.LDP;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.smallrye.reactive.messaging.kafka.Record;

public class TripleStoreRouter {
	private static final Logger LOGGER = getLogger(TripleStoreRouter.class);
	
    @Inject
    @ConfigProperty(name = "trellis.triplestore-update-url", defaultValue = "http://localhost:3030/ds/update")
    URI triplestoreUpdateUrl;
    
	private ExecutorService executorService = Executors.newFixedThreadPool(5);
	
	@Incoming("triplestore")
	public void processActivity(Record<String, String> record) {
		final String iri = record.key();
		LOGGER.debug("triple store indexing task: {}", iri);
		JsonNode as;
		try {
			as = new ObjectMapper().readTree(record.value());
		} catch (JsonProcessingException e) {
			LOGGER.error("cannot parse activitystream", e);
			return;
		}
		boolean isContainer = StreamSupport.stream(((ArrayNode)as.at("/object/type")).spliterator(), false)
				.map(JsonNode::asText)
				.anyMatch(t -> { return LDP.Container.getIRIString().equals(t); });
		switch(((ArrayNode)as.at("/type")).get(1).asText()) {
		case "Update": 
			CompletableFuture<String> getBody = CompletableFuture.runAsync(() -> delete(iri), executorService)
				.thenApply((Void) -> get(iri));
			getBody.thenAccept(body -> create(iri, body));
			if(isContainer) {
				CompletableFuture.runAsync(() -> deleteContains(iri), executorService)
					.thenCombine(getBody, (Void, body) -> { return body; })
					.thenAccept(body -> {
						createContains(iri, body);
					});
			}
			break;
		case "Create":
			CompletableFuture<String> s = CompletableFuture.supplyAsync(() -> { return get(iri); }, executorService);
			s.thenAccept(body -> create(iri, body));
			if(isContainer) {
				s.thenAccept(body -> createContains(iri, body));
			}
			break;
		case "Delete":
			CompletableFuture.runAsync(() -> delete(iri), executorService);
			if(isContainer) {
				CompletableFuture.runAsync(() -> deleteContains(iri), executorService);
			}
		}
	}


	private String get(String iri) {
		try {
			HttpClient http = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(localhost(iri)).GET()
				.header("Prefer", "return=representation;")
				.header("Accept", "application/n-triples")
				.build();
			return http.send(req, BodyHandlers.ofString()).body();
		} catch (IOException | InterruptedException | URISyntaxException e) {
			LOGGER.error("Cannot get triples for {}", iri, e);
			throw new CompletionException(e);
		}
	}
	
	private void create(String iri, String body) {
		String insert = "INSERT DATA { GRAPH <"+iri+"> { "+ body + " } };";
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(triplestoreUpdateUrl).method("POST", BodyPublishers.ofString(sparqlUpdate(insert)))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .build();
		try {
			http.send(req, BodyHandlers.discarding());
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Problem posting object triples", e);
		}
	}
	
	private void createContains(String iri, String body) {
		List<Triple> triples = new ArrayList<Triple>();
		try {
		RDFParser.create().lang(Lang.TURTLE).source(new StringReader(body)).parse(StreamRDFLib.sinkTriples(new SinkToCollection<Triple>(triples)));
		} catch(Exception e) {
			LOGGER.error("something", e);
		}
		if(triples.size() == 0) return;
		String containsTriples = triples.stream().filter(t -> t.getPredicate().getURI().equals("http://www.w3.org/ns/ldp#contains"))
				.map(t -> String.format("<%s> <%s> <%s> .\n", t.getSubject().getURI(), t.getPredicate().getURI(), t.getObject().getURI())).reduce("", (head, next) -> head+next);
		String insert = "INSERT DATA { GRAPH <https://example.nps.gov/2021/nps-workflow#containsGraph> { " + containsTriples + " } };";
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(triplestoreUpdateUrl).method("POST", BodyPublishers.ofString(sparqlUpdate(insert)))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .build();
		try {
			http.send(req, BodyHandlers.discarding());
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Problem posting object contains triples", e);
		}
	}


	private void delete(String iri) {
		String bodyA = sparqlUpdate("DELETE WHERE { GRAPH <" + iri + "> { ?s ?p ?o } };");
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest reqA = HttpRequest.newBuilder(triplestoreUpdateUrl).method("POST", BodyPublishers.ofString(bodyA))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .build();
		try {
			http.send(reqA, BodyHandlers.discarding());
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Problem deleting object graph", e);
		}
	}
	
	private void deleteContains(String iri) {
		String body = "DELETE WHERE { GRAPH <https://example.nps.gov/2021/nps-workflow#containsGraph> { <"+ iri +"> <http://www.w3.org/ns/ldp#contains> ?o } };";
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(triplestoreUpdateUrl).method("POST", BodyPublishers.ofString(sparqlUpdate(body)))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .build();
		try {
			http.send(req, BodyHandlers.discarding());
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Problem deleting contains triples", e);
		}
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

    public static String sparqlUpdate(final String command) {
    	return "update=" + encode(command, "UTF-8");
    }
}
