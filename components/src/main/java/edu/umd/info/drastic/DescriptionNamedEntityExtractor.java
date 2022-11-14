package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.getGraph;
import static edu.umd.info.drastic.LDPHttpUtil.patchGraph;
import static edu.umd.info.drastic.NPSVocabulary.ICMS_FULLTEXT_EXCLUSIONS;
import static edu.umd.info.drastic.NPSVocabulary.ICMS_NS;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.Literal;
import org.apache.commons.rdf.api.RDF;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment.Strategy;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.api.RDFFactory;
import org.trellisldp.vocabulary.Trellis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.umd.info.drastic.NPSVocabulary.ICMS;
import edu.umd.info.drastic.NPSVocabulary.TIKA;
import io.smallrye.reactive.messaging.annotations.Blocking;

/**
 * This class is responsible for extracting named entity phrases from archival descriptions.
 * Description full text is built from ICMS triples with certain exclusions. Entity results are stored in
 * their own set of named entity triples.
 * 
 * @author jansen
 *
 */
/**
 * @author jansen
 *
 */
public class DescriptionNamedEntityExtractor {
	private static final Logger LOGGER = getLogger(DescriptionNamedEntityExtractor.class);
	
	private static final RDF rdf = RDFFactory.getInstance();
    
    @Inject
    @ConfigProperty(name = "trellis.tika-baseurl", defaultValue = "http://tika:9998")
    URI tikaBaseURL;
    
	@Incoming("desc-ner-in")
	@Blocking("tika")
	@Acknowledgment(Strategy.PRE_PROCESSING)
	public void processNewGraph(String msg) {
		LOGGER.info("Got NER request for: {}", msg);
		if(!DrasticPaths.descriptions.matches(msg)) return;
		IRI iri = rdf.createIRI(msg);
		Graph priorGraph = getGraph(iri.getIRIString());
		if (priorGraph.contains(iri, org.trellisldp.vocabulary.RDF.type, ICMS.RediscoveryExport.iri)) {
			Dataset d = rdf.createDataset();
			Graph g = d.getGraph(Trellis.PreferUserManaged).get(); // holds new statements
			String fulltext = priorGraph.stream()
				.filter(n -> {
					String pred = n.getPredicate().getIRIString();
					if(!pred.startsWith(ICMS_NS.getIRIString())) return false;
					if(!(n.getObject() instanceof Literal)) return false;
					ICMS predEnum = null;
					try {
						predEnum = ICMS.valueOf(pred.substring(ICMS_NS.getIRIString().length()));
					} catch(IllegalArgumentException e) {
						return false;
					}
					return !ICMS_FULLTEXT_EXCLUSIONS.contains(predEnum);
					})
				.map(n -> {
					Literal l = (Literal)n.getObject();
					return l.getLexicalForm();
					})
				.collect(Collectors.joining(" "));
			try {
				String json = tikaNER(fulltext);
				JsonNode js = new ObjectMapper().readTree(json);
				for(TIKA key : TIKA.values()) {
					if(js.has(key.name())) {
						for(String phrase : js.findValuesAsText(key.name())) {
							if(phrase.trim().length() > 0) {
								IRI b = rdf.createIRI("urn:uuid:"+UUID.randomUUID().toString());
								g.add(iri, NPSVocabulary.NPS.hasProposedEntity.iri, b);
								g.add(b, NPSVocabulary.NPS.entityType.iri, key.iri);
								g.add(b, NPSVocabulary.NPS.entityText.iri, rdf.createLiteral(phrase));
							}
						}
					}
				}
				if(g.size() > 0) patchGraph(g, iri.getIRIString());
			} catch (IOException | InterruptedException e) {
				LOGGER.warn("Something went wrong performing NER", e);
			}
		} else {
			LOGGER.warn("Found a resource matching \"/description/*\" w/o RediscoveryExport predicate: {}",
					iri);
		}
	}
	
	/**
	 * Calls Tika metadata service:
	 *   curl -T ~/Documents/imls_interim_performance_report_2016.pdf 
	 *     -H "Accept: application/json" http://localhost:9998/meta 
	 * @param fulltext
	 * @return a JSON string of results
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private String tikaNER(String fulltext) throws IOException, InterruptedException {
		URI metaEndpoint = this.tikaBaseURL.resolve("/meta");
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(metaEndpoint).PUT(BodyPublishers.ofString(fulltext)).header("Accept", "application/json")
				.build();
		String result = http.send(req, BodyHandlers.ofString()).body();
		return result;
	}

}
