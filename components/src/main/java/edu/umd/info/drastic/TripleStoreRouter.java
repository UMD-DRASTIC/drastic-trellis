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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.StreamSupport;

import javax.inject.Inject;

import org.apache.jena.atlas.lib.SinkToCollection;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDFLib;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.slf4j.Logger;
import org.trellisldp.vocabulary.LDP;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.smallrye.mutiny.Uni;

public class TripleStoreRouter {
	private static final Logger LOGGER = getLogger(TripleStoreRouter.class);
	
    @Inject
    @ConfigProperty(name = "trellis.triplestore-update-url", defaultValue = "http://localhost:3030/ds/update")
    URI triplestoreUpdateUrl;
	
	@Incoming("triplestore")
	@Outgoing("triplestore-newgraph")
	public Uni<Message<String>> processMutiny(Message<String> activityStream) {
		return Uni.createFrom().item(activityStream.getPayload()).onItem().transform(msg -> {
			LOGGER.debug("Got activity stream: {}", msg);
			try {
				return new ObjectMapper().readTree(msg);
			} catch (JsonProcessingException e) {
				LOGGER.debug("triple store AS json parsing failed", e);
				throw new CompletionException("triple store AS json parsing failed", e);
			}
		}).onItem().transformToUni(json -> {
			boolean isContainer = StreamSupport.stream(((ArrayNode)json.at("/object/type")).spliterator(), false)
					.map(JsonNode::asText)
					.anyMatch(t -> LDP.Container.getIRIString().equals(t));
			boolean isNonRDFSource = StreamSupport.stream(((ArrayNode)json.at("/object/type")).spliterator(), false)
					.map(JsonNode::asText)
					.anyMatch(t -> LDP.NonRDFSource.getIRIString().equals(t));
			String op = ((ArrayNode)json.at("/type")).get(1).asText();
			String iri = json.get("object").get("id").asText();
			LOGGER.debug("triple store process: {} {} Container:{} NonRDFSource:{}", op, iri, isContainer, isNonRDFSource);
			if(isNonRDFSource && !iri.endsWith("?ext=description")) {
				LOGGER.debug("skipping triple store indexing of a binary node: {}", iri);
				return Uni.createFrom().nothing();  // no downstream events
			}
			if(!"Create".equals(op)) { // Update or Delete
				delete(iri);
				if(isContainer) deleteContains(iri);
			}
			if(!"Delete".equals(op)) { // Update or Create
				String body = get(iri);
				create(iri, body);
				if(isContainer) createContains(iri, body);
			}
			return Uni.createFrom().item(Message.of(iri));
		}).onItem().delayIt().by(Duration.ofSeconds(10));
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
		LOGGER.debug("triple store insert:\n{}", insert);
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
