package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;

import javax.inject.Inject;

import org.apache.commons.rdf.api.BlankNode;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.Triple;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment.Strategy;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.vocabulary.SKOS;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jsonldjava.core.RDFDataset.Literal;

import edu.umd.info.drastic.NPSVocabulary.SKOS_XL;
import io.smallrye.reactive.messaging.annotations.Blocking;

/**
 * The AuthorityRecordSKOSResourceIndexer is responsible for indexing new and updated SKOS
 * resources in a special name-authority Elasticsearch index. This is triggered via the 
 * new/updated/deleted "objects" topic.
 * SKOS is added to the repository as an LDP-RS under the path /name-authority/**.
 *
 * @author jansen
 *
 */
public class AuthorityRecordSKOSResourceIndexer {
	private static final Logger LOGGER = getLogger(AuthorityRecordSKOSResourceIndexer.class);

    @Inject
    @ConfigProperty(name = "trellis.elasticsearch-url", defaultValue = "http://localhost:9200/")
    URI elasticSearchUrl;

    final JsonNodeFactory factory = JsonNodeFactory.instance;

	@Incoming("authrec-index")
	@Blocking("elasticsearch-suppliers")
	@Acknowledgment(Strategy.PRE_PROCESSING)
	public void run(String iri) {
		if(!DrasticPaths.name_authority.matches(iri)) return;
		LOGGER.info("Reindexing name authorities in Elasticsearch: {}", iri);
		Graph g = LDPHttpUtil.getGraph(iri);
		
		// get all the Concept ids
		try {
		String es_bulk = g.stream(null, org.trellisldp.vocabulary.RDF.type, SKOS.Concept)
			.map(Triple::getSubject)
			.filter(obj -> obj instanceof IRI)
			.map(IRI.class::cast)
		    .map(s -> {
		    	String id = s.getIRIString();
				ObjectNode action = factory.objectNode();
				action.set("index", factory.objectNode()
						.<ObjectNode>set("_id", factory.textNode(id)).set("_index", factory.textNode("authority-records")));
				
				ObjectNode source = factory.objectNode();
				source.set("id", factory.textNode(id));
				
				ArrayNode types = factory.arrayNode();
				g.stream(s, org.trellisldp.vocabulary.RDF.type, null)
		    			.map(Triple::getObject).filter(obj -> obj instanceof IRI).map(IRI.class::cast)
		    			.map(IRI::getIRIString).forEach(t -> types.add(factory.textNode(t)));
		    	source.set("types", types);
		    	
		    	String pref = g.stream(s, SKOS.prefLabel, null).findFirst().orElseThrow().getObject().toString();
		    	source.set("prefLabel", factory.textNode(pref));
		    	
		    	ArrayNode altLabels = factory.arrayNode();
		    	g.stream(s, SKOS.altLabel, null).map(Triple::getObject)
		    			.filter(obj -> obj instanceof BlankNode)
		    			.map(BlankNode.class::cast)
		    			.forEach(b -> {
		    				Literal l = (Literal)g.stream(b, SKOS_XL.literalForm.iri, null).findFirst().orElseThrow()
		    						.getObject();
		    				altLabels.add(factory.textNode(l.getValue()));
		    			});
		    	source.set("altLabels", altLabels);
		    	return Arrays.asList(action, source);
		    }).flatMap(list -> list.stream()).<StringBuilder>collect(StringBuilder::new,
					(b, x) -> {	b.append(x); b.append("\n"); },
					(a, b) -> {	a.append(b.toString()); }).toString();
		
			postElasticDocument(es_bulk);
		} catch(NoSuchElementException e) {
			LOGGER.error("Error while attempting to index a SKOS record: {}", iri, e);
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
