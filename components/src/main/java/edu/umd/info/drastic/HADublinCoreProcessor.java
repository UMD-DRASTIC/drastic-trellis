package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.enterprise.context.ApplicationScoped;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;

import io.smallrye.reactive.messaging.kafka.Record;

/**
 * A {@link RouteBuilder} that forwards the Trellis object activity to Kafka.
 * <p>
 * Note that for the {@code @Inject} and {@code @ConfigProperty} annotations to work, this class has to be annotated
 * with {@code @ApplicationScoped}.
 */
@ApplicationScoped
public class HADublinCoreProcessor {

	private static final Logger LOGGER = getLogger(HADublinCoreProcessor.class);

	public static final Set<String> dcElements = new HashSet<String>(Arrays.asList(
			"identifier", "title", "creator", "subject", "description", "date", "rights"));

	private ExecutorService executorService = Executors.newFixedThreadPool(1);
	
	@Incoming("dce")
    public void process(Record<String, String> record) {
		if(NPSFilenameUtil.isDCSheet(record.key())) {
			LOGGER.debug("Got a new DC manifest: {}", record.key());
			URI binaryLoc;
			try {
				binaryLoc = new URI(record.key());
				CompletableFuture.runAsync(() -> processDCFile(binaryLoc), executorService);
			} catch (URISyntaxException e2) {
				throw new Error("Unexpected error", e2);
			}			
		}
	}	
	
	private void processDCFile(URI binaryLoc) {
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(binaryLoc).GET().build();
		File sheetFile;
		File extractedFile;
		try {
			sheetFile = File.createTempFile("tmp", ".xlsx");
			extractedFile = File.createTempFile("tmp", ".ttl");
			http.send(req, HttpResponse.BodyHandlers.ofFile(sheetFile.toPath()));
		} catch (IOException | InterruptedException e1) {
			LOGGER.error("Cannot download temp xslx file", e1);
			throw new Error(e1);
		}
		try(Workbook workbook = WorkbookFactory.create(sheetFile)) {
			Sheet sheet = workbook.getSheetAt(0);
			
			URL base = NPSFilenameUtil.getSubmissionUrl(binaryLoc.toURL());
			try(PrintWriter pw = new PrintWriter(extractedFile)) {
				writeRDF(pw, sheet, base);
			}
			
			URI extractedLoc = new URL(binaryLoc.toURL(), "./"+NPSFilenameUtil.getExtractedDCFilename(binaryLoc.toASCIIString())).toURI();
			HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofFile(extractedFile.toPath());
			HttpResponse<Void> hres = http.send(HttpRequest.newBuilder(extractedLoc)
					.method("PUT", publisher)
					.header("Link", "<http://www.w3.org/ns/ldp#RDFSource>; rel=\"type\"")
					.header("Content-Type", "text/turtle")
					.build(), BodyHandlers.discarding());
			if(hres.statusCode() != 201) {
				LOGGER.error("Problem putting extracted RDF: {}", hres.statusCode());
				return;
			}
		} catch (Exception e) {
			LOGGER.debug("problem", e);
			throw new Error(e);
		}
	}
	
	private void writeRDF(Writer out, Sheet sheet, URL base) throws IOException {
		out.write("@prefix dce: <http://purl.org/dc/elements/1.1/> .\n");
		out.write("@prefix xsd:   <http://www.w3.org/2001/XMLSchema#> .\n\n");
		boolean columnsIdentified = false;
		Map<Integer, String> columns2dc = new HashMap<Integer, String>();
		for (Row row : sheet) { // skips empty row already
			LOGGER.debug("processing row {}", row.getRowNum());
			if(row.getCell(0) == null) {
				LOGGER.debug("skipping blank row {}", row.getRowNum());
				continue;
			}
			if(row.getCell(0).getCellType() == CellType.STRING && row.getCell(0).getStringCellValue() == "") {
				LOGGER.debug("skipping blank row {}", row.getRowNum());
				continue;
			}
			if(!columnsIdentified) {
				for(Cell c : row) {
					if(c.getCellType() == CellType.STRING) {
						if(dcElements.contains(c.getStringCellValue().toLowerCase())) {
							columns2dc.put(Integer.valueOf(c.getColumnIndex()), c.getStringCellValue().toLowerCase());
						}
					}
				}
				LOGGER.debug("columns identified {}", columns2dc.size());
				columnsIdentified = true;
			} else {
				Map<String, String> dce = new HashMap<String, String>();
				for(Cell c : row) {
					Integer key = Integer.valueOf(c.getColumnIndex());
					if(columns2dc.containsKey(key)) {
						dce.put(columns2dc.get(key), c.getStringCellValue());
					}
				}
				URI doc;
				try {
					doc = new URI(base.toExternalForm()+"/"+dce.get("identifier"));
				} catch (URISyntaxException e1) {
					LOGGER.error("Unexpected", e1);
					throw new Error(e1);
				}
				out.write("<" + doc.toASCIIString() + "> ");
				Optional<String> statements = dce.entrySet().stream().map((e) -> {
					String val = e.getKey().equals("date") ? "\""+e.getValue()+"\"^^xsd:date" : "\""+e.getValue().replace("\"", "\\\"")+"\"";            
					return "dce:"+e.getKey()+" "+val;
				}).reduce((a, b) -> { return a+";\n\t"+b; });
				if(statements.isPresent()) {
					out.write(statements.get());
					out.write(" .\n\n");
				}
			}
		}
	}	
}