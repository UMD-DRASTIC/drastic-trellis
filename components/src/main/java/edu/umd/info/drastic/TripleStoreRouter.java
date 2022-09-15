package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.getGraph;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;

import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.Triple;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.slf4j.Logger;
import org.trellisldp.vocabulary.LDP;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.smallrye.reactive.messaging.annotations.Blocking;

public class TripleStoreRouter {
	private static final Logger LOGGER = getLogger(TripleStoreRouter.class);
	
    @Inject
    @ConfigProperty(name = "trellis.triplestore-update-url", defaultValue = "http://localhost:3030/ds/update")
    URI triplestoreUpdateUrl;
	
	@Incoming("triplestore")
	@Outgoing("triplestore-newgraph")
	@Blocking("triplestore-suppliers")
	public String process(String activityStream) {
		JsonNode json = null;
		try {
			json = new ObjectMapper().readTree(activityStream);
		} catch (JsonProcessingException e) {
			LOGGER.warn("triple store AS json parsing failed", e);
			throw new CompletionException("triple store AS json parsing failed", e);
		}
		boolean isContainer = StreamSupport.stream(((ArrayNode)json.at("/object/type")).spliterator(), false)
				.map(JsonNode::asText)
				.anyMatch(t -> LDP.Container.getIRIString().equals(t));
		String op = ((ArrayNode)json.at("/type")).get(1).asText();
		String iri = json.get("object").get("id").asText();
		//LOGGER.debug("triple store process: {} {} Container:{} NonRDFSource:{}", op, iri, isContainer, isNonRDFSource);
		if(!"Create".equals(op)) { // Update or Delete
			delete(iri);
			if(isContainer) deleteContains(iri);
		}
		if(!"Delete".equals(op)) { // Update or Create
			Graph g = getGraph(iri);
			Map<Boolean, List<Triple>> splitGraph = g.stream()
					.collect(Collectors.<Triple>partitioningBy(t -> {
						return t.getPredicate().getIRIString().equals("http://www.w3.org/ns/ldp#contains");
					}));
			if(isContainer && splitGraph.get(Boolean.TRUE).size() > 0) {
				createContains(splitGraph.get(Boolean.TRUE));
			}
			create(iri, splitGraph.get(Boolean.FALSE));
		}
		try {
			Thread.sleep(200);
		} catch (InterruptedException unexpected) {
			throw new CompletionException(unexpected);
		}
		return iri;
	}
	
	private void create(String iri, List<Triple> g) {
		if(g.size() == 0) return;
		String serialized = g.stream().map(t -> MessageFormat.format("{0} {1} {2} .", 
				t.getSubject().ntriplesString(),
				t.getPredicate().ntriplesString(),
				t.getObject().ntriplesString())).collect(Collectors.joining("\n"));
		String insert = "INSERT DATA { GRAPH <"+iri+"> { "+ serialized + " } };";
		//LOGGER.debug("triple store insert:\n{}", insert);
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
	
	private void createContains(List<Triple> g) {
		String serialized = g.stream().map(t -> MessageFormat.format("{0} {1} {2} .", 
				t.getSubject().ntriplesString(),
				t.getPredicate().ntriplesString(),
				t.getObject().ntriplesString())).collect(Collectors.joining("\n"));
		//LOGGER.debug("contains graph insert: {}", serialized);
		String insert = "INSERT DATA { GRAPH <https://example.nps.gov/2021/nps-workflow#containsGraph> { " + serialized + " } };";
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
