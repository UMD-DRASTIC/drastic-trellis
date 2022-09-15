package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.localhost;
import static edu.umd.info.drastic.LDPHttpUtil.patchGraph;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Link;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.rdf.api.BlankNode;
import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.api.RDFFactory;
import org.trellisldp.vocabulary.Trellis;

import io.smallrye.reactive.messaging.annotations.Blocking;
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

	private final RDF rdf = RDFFactory.getInstance();

	private ExecutorService executorService = Executors.newFixedThreadPool(1);
	
	@Incoming("fixity")
	@Blocking
    public void process(Record<String, String> record) {
		IRI id = rdf.createIRI(record.key());
		LOGGER.debug("digest processor task: {}", id);

		URI binaryLoc;
		try {
			binaryLoc = localhost(record.key());
		} catch (URISyntaxException e2) {
			LOGGER.error("Got a failure when building binary url.", e2);
			return;
		}
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(binaryLoc).GET().build();
		CompletableFuture.supplyAsync(() -> {
			try {
				return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
			} catch (IOException | InterruptedException e) {
				LOGGER.error("Got a failure when requesting binary for digest.", e);
				throw new CompletionException(e);
			}
		}, executorService).thenAccept(res -> {
			List<String> links = res.headers().allValues(HttpHeaders.LINK);
			final URI descriptionLoc = links.stream().map(Link::valueOf).filter(link -> "describedby".equals(link.getRel())).peek(System.out::println)
					.map(Link::getUri).findFirst().orElse(null);
			MessageDigest md5 = DigestUtils.getDigest("md5");
			MessageDigest sha256 = DigestUtils.getDigest("SHA-256");
	        DigestInputStream md5DigestStream = new DigestInputStream(res.body(), md5);
	        DigestInputStream sha256DigestStream = new DigestInputStream(md5DigestStream, sha256);
	        byte[] buffer = new byte[8 * 1024];
	        try {
	        	while ((sha256DigestStream.read(buffer)) != -1) {}
	        } catch(IOException e) {
	        	LOGGER.error("Got a failure when calculating digest.", e);
						return;
	        }
	        String myMD5 = hex(md5DigestStream.getMessageDigest().digest());
	        String mySHA256 = hex(sha256DigestStream.getMessageDigest().digest());
	        patchDigests(id, descriptionLoc, myMD5, mySHA256);
		});
	}

	private String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
	}

	private void patchDigests(IRI id, URI descriptionLoc, String md5, String sha256) {
    Dataset d = rdf.createDataset();
    BlankNode fixMD5 = rdf.createBlankNode("fixityMD5");
    Graph g = d.getGraph(Trellis.PreferUserManaged).get();
    g.add(id, rdf.createIRI("http://www.loc.gov/standards/premis/rdf/v3/fixity"), fixMD5);
    g.add(fixMD5, rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), rdf.createIRI("http://id.loc.gov/vocabulary/cryptographicHashFunctions/md5"));
    g.add(fixMD5, rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#value"), rdf.createLiteral(md5));
    g.add(fixMD5, rdf.createIRI("http://purl.org/dc/elements/1.1/creator"), rdf.createLiteral("java.security.MessageDigest"));

    BlankNode fixSHA256 = rdf.createBlankNode("fixitySHA256");
    g.add(id, rdf.createIRI("http://www.loc.gov/standards/premis/rdf/v3/fixity"), fixSHA256);
    g.add(fixSHA256, rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#value"), rdf.createLiteral(sha256));
    g.add(fixSHA256, rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), rdf.createIRI("http://id.loc.gov/vocabulary/cryptographicHashFunctions/sha256"));
    g.add(fixSHA256, rdf.createIRI("http://purl.org/dc/elements/1.1/creator"), rdf.createLiteral("java.security.MessageDigest"));

    patchGraph(g, descriptionLoc.toASCIIString());
  }

}
