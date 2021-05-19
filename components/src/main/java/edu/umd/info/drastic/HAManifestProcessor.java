package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.enterprise.context.ApplicationScoped;

import org.apache.commons.rdf.api.BlankNode;
import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.trellisldp.api.RDFFactory;
import org.trellisldp.vocabulary.Trellis;

import io.smallrye.reactive.messaging.kafka.Record;

/**
 * A {@link RouteBuilder} that forwards the Trellis object activity to Kafka.
 * <p>
 * Note that for the {@code @Inject} and {@code @ConfigProperty} annotations to work, this class has to be annotated
 * with {@code @ApplicationScoped}.
 */
@ApplicationScoped
public class HAManifestProcessor {

	private static final Logger LOGGER = getLogger(HAManifestProcessor.class);
	
	private final RDF rdf = RDFFactory.getInstance();
	
	private ExecutorService executorService = Executors.newFixedThreadPool(1);
	
	@Incoming("manifest")
    public void process(Record<String, String> record) {
		if(NPSFilenameUtil.isMD5Sheet(record.key())) {
			LOGGER.debug("Got a new MD5 manifest: {}", record.key());
			URI binaryLoc;
			try {
				binaryLoc = new URI(record.key());
			} catch (URISyntaxException e2) {
				throw new Error("Unexpected error", e2);
			}			
			CompletableFuture.runAsync(() -> extractFilenames2MD5Map(binaryLoc), executorService);
		}
	}	
	
	private void extractFilenames2MD5Map(URI binaryLoc) {
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(binaryLoc).GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
			.thenAccept(res -> {
				InputStream is = res.body();
				try(Workbook workbook = WorkbookFactory.create(new BufferedInputStream(is))) {
					LOGGER.debug("Got workbook");
					Sheet sheet = workbook.getSheetAt(0);
					boolean md5Seen = false;
					for (Row row : sheet) {
						try {
							Cell f = row.getCell(0);
							if(f.getCellType() != CellType.STRING) continue;
							String filename = f.getStringCellValue();
							if(!filename.contains("\\")) continue;
							filename = filename.substring(filename.lastIndexOf('\\')+1);
							if(!NPSFilenameUtil.isHierarchalConvention(filename)) continue;
							Cell m = row.getCell(1);
							if(m.getCellType() != CellType.STRING) continue;
							md5Seen = true;
							addManifestDigest(filename, m.getStringCellValue().trim(), binaryLoc);
						} catch(Exception e) {
							if(md5Seen) return;
							LOGGER.error("Error processing manifest row", e);
							 // TODO flag file for error
						}
						Thread.sleep(1000 * 1);
					}
				} catch (Exception e) {
					throw new Error(e);
				}
			});
	}
	
	private void addManifestDigest(String filename, String md5, URI manifestLoc) throws IOException, InterruptedException {
		URI fileDescLoc;
		IRI fileID;
		try {
			fileDescLoc = new URL(manifestLoc.toURL(), filename+"?ext=description").toURI();
			fileID = rdf.createIRI(new URL(manifestLoc.toURL(), filename).toExternalForm());
		} catch (MalformedURLException | URISyntaxException e) {
			throw new Error("Unexpected URL error", e);
		}
		HttpClient http = HttpClient.newHttpClient();
		HttpResponse<Void> hres = http.send(HttpRequest.newBuilder(fileDescLoc).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.discarding());
		if(hres.statusCode() != 200) {
			LOGGER.debug("skipping non-existent file, response: {}", hres.statusCode());
			return;
		}
        Dataset d = rdf.createDataset();
        BlankNode fixMD5 = rdf.createBlankNode("fixityMD5");
        Graph g = d.getGraph(Trellis.PreferUserManaged).get();
        g.add(fileID, rdf.createIRI("http://www.loc.gov/standards/premis/rdf/v3/fixity"), fixMD5);
        g.add(fixMD5, rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), rdf.createIRI("http://id.loc.gov/vocabulary/cryptographicHashFunctions/md5"));
        g.add(fixMD5, rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#value"), rdf.createLiteral(md5));
        g.add(fixMD5, rdf.createIRI("http://purl.org/dc/elements/1.1/creator"), rdf.createLiteral("History & Associates"));
        g.add(fixMD5, rdf.createIRI("http://purl.org/dc/elements/1.1/source"), rdf.createIRI(manifestLoc.toString()));
        LOGGER.debug("revised graph: {}", g);
        String patch = "INSERT { "+ g.toString() +" }" +
        		"WHERE { }";
		HttpResponse<Void> res = http.send(HttpRequest.newBuilder(fileDescLoc).method("PATCH", HttpRequest.BodyPublishers.ofString(patch))
				.header("Content-type", "application/sparql-update").build(), HttpResponse.BodyHandlers.discarding());
		if(res.statusCode() == 404) {
			LOGGER.debug("404 for manifest entry");
		} else if(res.statusCode() != 204) {
			LOGGER.error("Got a failure when patching binary description for manifest md5: {}", res.statusCode());
		}
    }
	
}