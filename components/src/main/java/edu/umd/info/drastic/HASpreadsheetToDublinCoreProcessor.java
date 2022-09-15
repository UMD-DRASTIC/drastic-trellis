package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.localhost;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
public class HASpreadsheetToDublinCoreProcessor {

	private static final Logger LOGGER = getLogger(HASpreadsheetToDublinCoreProcessor.class);

	private ExecutorService executorService = Executors.newFixedThreadPool(1);

	@Incoming("spreadsheet2dc")
    public void process(Record<String, String> record) {
		if(NPSFilenameUtil.isDCSheet(record.key())) {
			LOGGER.debug("Dublin Core spreadsheet task: {}", record.key());
			URI binaryLoc;
			try {
				binaryLoc = new URI(record.key());
				CompletableFuture.runAsync(() -> processDCFile(binaryLoc), executorService);
			} catch (URISyntaxException e2) {
				LOGGER.error("Got a failure when processing dublin core spreadsheet", e2);
			}
		}
	}

	private void processDCFile(URI binaryLoc) {
		File sheetFile;
		File extractedFile;
		try {
			HttpClient http = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(localhost(binaryLoc)).GET().build();
			sheetFile = File.createTempFile("tmp", ".xlsx");
			extractedFile = File.createTempFile("tmp", ".ttl");
			http.send(req, HttpResponse.BodyHandlers.ofFile(sheetFile.toPath()));
		} catch (IOException | InterruptedException | URISyntaxException e1) {
			LOGGER.error("Cannot download temp xslx file", e1);
			return;
		}
		try(Workbook workbook = WorkbookFactory.create(sheetFile)) {
			Sheet sheet = workbook.getSheetAt(0);

			URL base = NPSFilenameUtil.getSubmissionUrl(binaryLoc.toURL());
			try(PrintWriter pw = new PrintWriter(extractedFile)) {
				writeRDF(pw, sheet, base);
			}

			URI extractedLoc = new URL(binaryLoc.toURL(), "./"+NPSFilenameUtil.getExtractedDCFilename(binaryLoc.toASCIIString())).toURI();
			HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofFile(extractedFile.toPath());
			HttpClient http = HttpClient.newHttpClient();
			HttpResponse<Void> hres = http.send(HttpRequest.newBuilder(localhost(extractedLoc))
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
			return;
		}
	}

	private void writeRDF(Writer out, Sheet sheet, URL base) throws IOException {
		out.write("@prefix dcterms: <http://purl.org/dc/terms/> .\n");
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
						try {
							NPSVocabulary.DCTERMS.valueOf(c.getStringCellValue().toLowerCase());
							columns2dc.put(Integer.valueOf(c.getColumnIndex()), c.getStringCellValue().toLowerCase());
						} catch(IllegalArgumentException ignored) {}
					}
				}
				LOGGER.debug("columns identified {}", columns2dc.size());
				columnsIdentified = true;
			} else {
				Map<String, String> dctermsPO = new HashMap<String, String>();
				for(Cell c : row) {
					Integer key = Integer.valueOf(c.getColumnIndex());
					if(columns2dc.containsKey(key)) {
						dctermsPO.put(columns2dc.get(key), c.getStringCellValue());
					}
				}
				URI doc;
				try {
					doc = new URI(base.toExternalForm()+"/"+dctermsPO.get("identifier"));
				} catch (URISyntaxException e1) {
					LOGGER.error("Unexpected", e1);
					return;
				}
				out.write("<" + doc.toASCIIString() + "> ");
				Optional<String> statements = dctermsPO.entrySet().stream().map((e) -> {
					String val = e.getKey().equals("date") ? "\""+e.getValue()+"\"^^xsd:date" : "\""+e.getValue().replace("\"", "\\\"")+"\"";
					return "dcterms:"+e.getKey()+" "+val;
				}).reduce((a, b) -> { return a+";\n\t"+b; });
				if(statements.isPresent()) {
					out.write(statements.get());
					out.write(" .\n\n");
				}
			}
		}
	}
}
