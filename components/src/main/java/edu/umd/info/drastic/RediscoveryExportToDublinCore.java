package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.getGraph;
import static edu.umd.info.drastic.LDPHttpUtil.patchGraph;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import javax.enterprise.context.ApplicationScoped;

import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.Literal;
import org.apache.commons.rdf.api.RDF;
import org.apache.commons.rdf.api.RDFTerm;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment.Strategy;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.api.RDFFactory;
import org.trellisldp.vocabulary.Trellis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import edu.umd.info.drastic.NPSVocabulary.DCTERMS;
import edu.umd.info.drastic.NPSVocabulary.ICMS;
import edu.umd.info.drastic.NPSVocabulary.ICMS_LEVEL;
import edu.umd.info.drastic.NPSVocabulary.PCDM;
import io.smallrye.reactive.messaging.annotations.Blocking;

/**
 * A {@link RouteBuilder} that forwards the Trellis object activity to Kafka.
 * <p>
 * Note that for the {@code @Inject} and {@code @ConfigProperty} annotations to
 * work, this class has to be annotated with {@code @ApplicationScoped}.
 */
@ApplicationScoped
public class RediscoveryExportToDublinCore {

	private static final Logger LOGGER = getLogger(RediscoveryExportToDublinCore.class);

	private final RDF rdf = RDFFactory.getInstance();

	/**
	 * Processes a newly CREATEd ICMS archival hierarchy record (collection, subgroup, series, subseries, box, folder, item)
	 * Converts some ICMS fields into Dublin Core Terms.
	 * 
	 * @param record
	 */
	@Incoming("icms2dc")
	@Blocking("trellis-suppliers")
	@Acknowledgment(Strategy.PRE_PROCESSING)
	public void process(String activityStream) {
		try {
			JsonNode js = new ObjectMapper().readTree(activityStream);
			IRI iri = rdf.createIRI(js.get("object").get("id").asText());
			// FIXME: lack of trailing slash is a *bug* that may be fixed
			boolean isRE = StreamSupport.stream(((ArrayNode) js.at("/object/type")).spliterator(), false).map(JsonNode::asText)
					.anyMatch(t -> ICMS.RediscoveryExport.str.equals(t));
			String op = ((ArrayNode)js.at("/type")).get(1).asText();
			if ( "Create".equals(op) && 
					(isRE || 
					(iri.getIRIString().contains("/description/") && !iri.getIRIString().endsWith("/description/")))) {
				Graph priorGraph = getGraph(iri.getIRIString());
				if (priorGraph.contains(iri, org.trellisldp.vocabulary.RDF.type, ICMS.RediscoveryExport.iri)) {
					//LOGGER.debug("Starting DC processing for {}", iri.getIRIString());
					Graph g = createStatements(iri, priorGraph);
					patchGraph(g, iri.getIRIString());
				} else {
					LOGGER.warn("Found a resource matching \"/description/*\" w/o RediscoveryExport predicate: {}",
							iri);
				}
			}
		} catch (JsonProcessingException e) {
			LOGGER.warn("Processing exception on JSON msg: {}", activityStream, e);
		}
	}

	private Graph createStatements(IRI id, Graph p) {
		// LOGGER.debug("Will attempt to patch RediscoveryExport with Dublin Core: {}", key);
		Dataset d = rdf.createDataset();
		Graph g = d.getGraph(Trellis.PreferUserManaged).get(); // holds new statements
		// Add PCDM Collection type
		g.add(id, org.trellisldp.vocabulary.RDF.type, PCDM.Collection.iri);

		// Map ICMS triples to DCTERMS with any transforms applied to the object.
		if (p.contains(id, NPSVocabulary.ICMS.level.iri, null)) {
			Literal level = (Literal) p.stream(id, NPSVocabulary.ICMS.level.iri, null).findFirst().get().getObject();

			// Map icms:level to dcterms:type, perhaps add "Archival" as in "archival
			// series".
			g.add(id, DCTERMS.type.iri, level);

			Map<IRI, Transform> map = icms2dcterms.get(ICMS_LEVEL.valueOf(level.getLexicalForm()));
			if (map == null) {
				LOGGER.warn("Found no Dublin Core mapping for {} with ICMS level of {}", id, level.getLexicalForm());
			} else {
				p.stream().filter(t -> {
					return map.containsKey(t.getPredicate());
				}).forEach(t -> {
					RDFTerm object = t.getObject();
					Transform trans = map.get(t.getPredicate());
					if (trans.transform != null) {
						object = trans.transform.apply(object);
					}
					g.add(id, trans.target.iri, object);
				});
			}
		}
		return g;
	}

	public static class Transform {
		DCTERMS target;
		Function<RDFTerm, RDFTerm> transform;

		Transform(DCTERMS target, Function<RDFTerm, RDFTerm> transform) {
			this.target = target;
			this.transform = transform;
		}
	}

	public static final Map<ICMS_LEVEL, Map<IRI, Transform>> icms2dcterms = new HashMap<ICMS_LEVEL, Map<IRI, Transform>>();
	static {
		Map<IRI, Transform> collMap = new HashMap<IRI, Transform>();
		collMap.put(ICMS.Collection_x0020_Title.iri, new Transform(DCTERMS.title, null));
		collMap.put(ICMS.Bulk_x0020_Dates.iri, new Transform(DCTERMS.date, null));
		collMap.put(ICMS.Collection_x0020_Nbr.iri, new Transform(DCTERMS.identifier, null));
		collMap.put(ICMS.Creator_x003A_Artist.iri, new Transform(DCTERMS.creator, null));
		collMap.put(ICMS.History_x003A_Brief_x0020_Note.iri, new Transform(DCTERMS.description, null));
		collMap.put(ICMS.History_x003A_Expansion_x0020_Note.iri, new Transform(DCTERMS.description, null));
		collMap.put(ICMS.Incl_x0020_Dates.iri, new Transform(DCTERMS.date, null));
		collMap.put(ICMS.Language_x003A_Language_x0020_Code.iri, new Transform(DCTERMS.language, null));
		collMap.put(ICMS.Scope_x003A_Expansion_x0020_Note.iri, new Transform(DCTERMS.description, null));
		collMap.put(ICMS.Scope_x003A_Summary_x0020_Note.iri, new Transform(DCTERMS.description, null));
		collMap.put(ICMS.Provenance.iri, new Transform(DCTERMS.ProvenanceStatement, null));
		icms2dcterms.put(ICMS_LEVEL.Collection, collMap);
		icms2dcterms.put(ICMS_LEVEL.Subgroup, collMap); // same same

		Map<IRI, Transform> seriesMap = new HashMap<IRI, Transform>();
		seriesMap.put(ICMS.Provenance.iri, new Transform(DCTERMS.ProvenanceStatement, null));
		seriesMap.put(ICMS.Scope_x003A_Expansion_x0020_Note.iri, new Transform(DCTERMS.description, null));
		seriesMap.put(ICMS.Series_x0020_Nbr.iri, new Transform(DCTERMS.identifier, null));
		seriesMap.put(ICMS.Series_x0020_Title.iri, new Transform(DCTERMS.title, null));
		seriesMap.put(ICMS.Language_x003A_Language_x0020_Code.iri, new Transform(DCTERMS.language, null));
		seriesMap.put(ICMS.Language_x003A_Language_x0020_Note.iri, new Transform(DCTERMS.language, null));
		seriesMap.put(ICMS.Incl_x0020_Dates.iri, new Transform(DCTERMS.date, null));
		seriesMap.put(ICMS.History_x003A_Expansion_x0020_Note.iri,
				new Transform(DCTERMS.description, null));
		seriesMap.put(ICMS.Collection_x0020_Nbr.iri, new Transform(DCTERMS.identifier, null));
		seriesMap.put(ICMS.Bulk_x0020_Dates.iri, new Transform(DCTERMS.date, null));
		icms2dcterms.put(ICMS_LEVEL.Series, seriesMap);
		icms2dcterms.put(ICMS_LEVEL.Subseries, seriesMap); // same same

		Map<IRI, Transform> fileUnitMap = new HashMap<IRI, Transform>();
		fileUnitMap.put(ICMS.Collection_x0020_Nbr.iri, new Transform(DCTERMS.identifier, null));
		fileUnitMap.put(ICMS.File_x0020_Unit_x0020_Nbr.iri, new Transform(DCTERMS.identifier, null));
		fileUnitMap.put(ICMS.Phys_x0020_Desc.iri, new Transform(DCTERMS.format, null));
		fileUnitMap.put(ICMS.Series_x0020_Nbr.iri, new Transform(DCTERMS.identifier, null));
		fileUnitMap.put(ICMS.Sp_x0020_Matl.iri, new Transform(DCTERMS.format, null));
		fileUnitMap.put(ICMS.Summ_x0020_Note.iri, new Transform(DCTERMS.description, null));
		fileUnitMap.put(ICMS.Title.iri, new Transform(DCTERMS.title, null));
		icms2dcterms.put(ICMS_LEVEL.Box, fileUnitMap);
		icms2dcterms.put(ICMS_LEVEL.Folder, fileUnitMap);
	}

}
