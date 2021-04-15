package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutionException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.rdf.api.BlankNode;
import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.api.Binary;
import org.trellisldp.api.BinaryMetadata;
import org.trellisldp.api.BinaryService;
import org.trellisldp.api.Metadata;
import org.trellisldp.api.RDFFactory;
import org.trellisldp.api.Resource;
import org.trellisldp.api.ResourceService;
import org.trellisldp.vocabulary.Trellis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.reactive.messaging.kafka.Record;

/**
 * A {@link RouteBuilder} that forwards the Trellis object activity to Kafka.
 * <p>
 * Note that for the {@code @Inject} and {@code @ConfigProperty} annotations to work, this class has to be annotated
 * with {@code @ApplicationScoped}.
 */
@ApplicationScoped
public class DigestProcessor {

	private static final Logger LOGGER = getLogger(DigestProcessor.class);
	
	@Inject
	BinaryService binaryService;
	
	@Inject
	ResourceService resourceService;
	
	private final RDF rdf = RDFFactory.getInstance();
	
	@Incoming("fixity")
    public void process(Record<String, String> record) throws IllegalArgumentException, InterruptedException, ExecutionException, IOException {
		LOGGER.info("fixity got a new binary: {}", record.key());
		JsonNode as;
		try {
			as = new ObjectMapper().readTree(record.value());
		} catch (JsonProcessingException e) {
			LOGGER.error("cannot parse activitystream", e);
			return;
		}
		IRI id = rdf.createIRI(as.at("/object/id").asText());
		LOGGER.info("got IRI: {}", id);
		Resource r = resourceService.get(id).toCompletableFuture().get();
		final IRI dsid = r.getBinaryMetadata().map(BinaryMetadata::getIdentifier).orElse(null);
		
		//IRI binaryId = r.getBinaryMetadata().get().getIdentifier();
		InputStream is = binaryService.get(dsid).toCompletableFuture().get().getContent();
		MessageDigest md5 = DigestUtils.getDigest("md5");
		MessageDigest sha256 = DigestUtils.getDigest("sha256");
        DigestInputStream md5DigestStream = new DigestInputStream(is, md5);
        DigestInputStream sha256DigestStream = new DigestInputStream(md5DigestStream, sha256);
        byte[] buffer = new byte[8 * 1024];
        while ((sha256DigestStream.read(buffer)) != -1) {}
        String myMD5 = Base64.encodeBase64String(md5DigestStream.getMessageDigest().digest());
        String mySHA256 = Base64.encodeBase64String(sha256DigestStream.getMessageDigest().digest());
        LOGGER.info("IRI: {}\nmd5: {}\nsha256: {}", id, myMD5, mySHA256);
        
        
        Dataset d = r.dataset();
        LOGGER.info("dataset: {}", d);
        
        BlankNode fixMD5 = rdf.createBlankNode("fixityMD5");
        Graph g = d.getGraph(Trellis.PreferUserManaged).get();
        g.add(id, rdf.createIRI("http://www.loc.gov/standards/premis/rdf/v3/fixity"), fixMD5);
        g.add(fixMD5, rdf.createIRI("rdf:type"), rdf.createIRI("http://id.loc.gov/vocabulary/cryptographicHashFunctions/md5"));
        g.add(fixMD5, rdf.createIRI("rdf:value"), rdf.createLiteral(myMD5));
        g.add(fixMD5, rdf.createIRI("http://purl.org/dc/elements/1-1/creator"), rdf.createLiteral("java.security.MessageDigest"));
        
        BlankNode fixSHA256 = rdf.createBlankNode("fixitySHA256");
        g.add(id, rdf.createIRI("http://www.loc.gov/standards/premis/rdf/v3/fixity"), fixSHA256);
        g.add(fixSHA256, rdf.createIRI("rdf:value"), rdf.createLiteral(mySHA256));
        g.add(fixSHA256, rdf.createIRI("rdf:type"), rdf.createIRI("http://id.loc.gov/vocabulary/cryptographicHashFunctions/sha256"));
        g.add(fixSHA256, rdf.createIRI("http://purl.org/dc/elements/1-1/creator"), rdf.createLiteral("java.security.MessageDigest"));
        
        LOGGER.info("revised graph: {}", g);
        Metadata m = Metadata.builder(r).build();
        resourceService.replace(m, d);
    }
}