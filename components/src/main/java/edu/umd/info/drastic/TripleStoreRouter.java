package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.StringReader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    @ConfigProperty(name = "trellis.triplestore-url", defaultValue = "http://localhost:3030/ds/update")
    URI triplestoreUrl;
	
	@Incoming("triplestore")
	public void processActivity(Record<String, String> record) {
		final String iri = record.key();
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
			CompletableFuture<String> getBody = delete(iri).thenCompose((Void) -> get(iri));
			getBody.thenCompose(body -> create(iri, body));
			if(isContainer) {
				deleteContains(iri).thenCombine(getBody, (Void, body) -> createContains(iri, body));
			}
			break;
		case "Create":
			CompletableFuture<String> s = get(iri).thenCompose(body -> create(iri, body));
			if(isContainer) {
				s.thenCompose(body -> createContains(iri, body));
			}
			break;
		case "Delete":
			delete(iri);
			if(isContainer) {
				deleteContains(iri);
			}
		}
	}


	private CompletableFuture<String> get(String iri) {
		URI uri;
		try {
			uri = new URI(iri);
		} catch (URISyntaxException e) {
			throw new Error("unexpected", e);
		}
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(uri).GET()
			.header("Prefer", "return=representation;")
			.header("Accept", "application/n-triples")
			.build();
		return http.sendAsync(req, BodyHandlers.ofString()).thenApply((res) -> {
					return res.body();
				});
	}
	
	private CompletableFuture<String> create(String iri, String body) {
		String insert = "INSERT DATA { GRAPH <"+iri+"> { "+ body + " } };";
		//LOGGER.debug("update body: {}", insert);
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(triplestoreUrl).method("POST", BodyPublishers.ofString(sparqlUpdate(insert)))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .build();
		return http.sendAsync(req, BodyHandlers.discarding()).thenApply((HttpResponse<Void> res2) -> {
				//LOGGER.debug("POST triples status {}", res2.statusCode());
				return body;
			});
	}
	
	private CompletableFuture<Void> createContains(String iri, String body) {
		//LOGGER.debug("contains body input: {}", body);
		List<Triple> triples = new ArrayList<Triple>();
		try {
		RDFParser.create().lang(Lang.TURTLE).source(new StringReader(body)).parse(StreamRDFLib.sinkTriples(new SinkToCollection<Triple>(triples)));
		} catch(Exception e) {
			LOGGER.error("something", e);
		}
		//LOGGER.debug("contains: parsed quads: {}", triples.size());
		if(triples.size() == 0) return null; // .map(t -> { LOGGER.debug(t.toString()); return t; })
		String containsTriples = triples.stream().filter(t -> t.getPredicate().getURI().equals("http://www.w3.org/ns/ldp#contains"))
				.map(t -> String.format("<%s> <%s> <%s> .\n", t.getSubject().getURI(), t.getPredicate().getURI(), t.getObject().getURI())).reduce("", (head, next) -> head+next);
		String insert = "INSERT DATA { GRAPH <https://example.nps.gov/2021/nps-workflow#containsGraph> { " + containsTriples + " } };";
		//LOGGER.debug("contains insert: {}", insert);
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(triplestoreUrl).method("POST", BodyPublishers.ofString(sparqlUpdate(insert)))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .build();
		return http.sendAsync(req, BodyHandlers.discarding()).thenAccept((HttpResponse<Void> res2) -> {
				//LOGGER.debug("POST contains triples status {}", res2.statusCode());
			});
	}


	private CompletableFuture<Void> delete(String iri) {
		String bodyA = sparqlUpdate("DELETE WHERE { GRAPH <" + iri + "> { ?s ?p ?o } };");
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest reqA = HttpRequest.newBuilder(triplestoreUrl).method("POST", BodyPublishers.ofString(bodyA))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .build();
		return http.sendAsync(reqA, BodyHandlers.discarding()).thenApply((r) -> { return null; } );
	}
	
	private CompletableFuture<Void> deleteContains(String iri) {
		String body = "DELETE WHERE { GRAPH <https://example.nps.gov/2021/nps-workflow#containsGraph> { <"+ iri +"> <http://www.w3.org/ns/ldp#contains> ?o } };";
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(triplestoreUrl).method("POST", BodyPublishers.ofString(sparqlUpdate(body)))
		        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
		        .build();
		return http.sendAsync(req, BodyHandlers.discarding()).thenAccept(r -> {
			//LOGGER.debug("deleted contains triples for {}", iri);
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

    public static String sparqlUpdate(final String command) {
    	return "update=" + encode(command, "UTF-8");
    }
}
