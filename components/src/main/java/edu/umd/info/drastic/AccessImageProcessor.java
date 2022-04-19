package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.localhost;
import static org.slf4j.LoggerFactory.getLogger;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.enterprise.context.ApplicationScoped;
import javax.imageio.ImageIO;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;

import io.smallrye.reactive.messaging.kafka.Record;

/**
 * A {@link RouteBuilder} that forwards the Trellis object activity to Kafka.
 * <p>
 * Note that for the {@code @Inject} and {@code @ConfigProperty} annotations to
 * work, this class has to be annotated with {@code @ApplicationScoped}.
 */
@ApplicationScoped
public class AccessImageProcessor {

	private static final Logger LOGGER = getLogger(AccessImageProcessor.class);

	public static final int MAX_THUMBNAIL_DIM = 256;

	private ExecutorService executorService = Executors.newFixedThreadPool(5);

	@Incoming("accessimage")
	public void process(Record<String, String> record) {
		LOGGER.debug("access image task: {}", record.key());
		if (/* NPSFilenameUtil.isHierarchalConvention(record.key()) && */  record.key().endsWith(".tif")) {
			CompletableFuture.runAsync(() -> {
				processImageFile(record.key());
			}, executorService);
		}
	}

	private void processImageFile(String binaryURIStr) {
		HttpClient http = HttpClient.newHttpClient();
		File accessImg = null;
		File thumbnailImg = null;
		try {
			URI binaryURI = localhost(binaryURIStr);
			HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder().uri(binaryURI).build(), BodyHandlers.ofInputStream());
			BufferedImage image = ImageIO.read(res.body());
			accessImg = File.createTempFile("foo", ".png");
			thumbnailImg = File.createTempFile("foo", ".png");
			ImageIO.write(image, "PNG", accessImg);
			String accessLoc = NPSFilenameUtil.getAccessImageURL(binaryURIStr);
			HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofFile(accessImg.toPath());
			try {
				HttpResponse<Void> hres = http.send(HttpRequest.newBuilder(localhost(accessLoc)).method("PUT", publisher)
					.header("Link", "<"+NPSVocabulary.LDP_NonRDFSource.getIRIString()+">; rel=\"type\"")
					.header("Content-Type", "image/png").build(), BodyHandlers.discarding());
				if (hres.statusCode() != 201) {
					LOGGER.error("Problem putting extracted RDF: {}", hres.statusCode());
					return;
				}
			} catch(IOException ignored) {}
			BufferedImage thumb = getThumbnailImage(image);
			ImageIO.write(thumb, "PNG", thumbnailImg);
			URI thumbnailLoc = localhost(NPSFilenameUtil.getThumbnailImageURL(binaryURIStr));
			HttpRequest.BodyPublisher pubThumb = HttpRequest.BodyPublishers.ofFile(thumbnailImg.toPath());
			try {
				http.send(HttpRequest.newBuilder(thumbnailLoc).method("PUT", pubThumb)
					.header("Link", "<"+NPSVocabulary.LDP_NonRDFSource.getIRIString()+">; rel=\"type\"")
					.header("Content-Type", "image/png").build(), BodyHandlers.discarding());
			} catch(IOException ignored) {}
		} catch (/*IOException | URISyntaxException | InterruptedException |*/ Exception e) {
			LOGGER.error("Unexpected problem", e);
			return;
		} finally {
			if(accessImg != null && accessImg.exists()) {
				accessImg.delete();
			}
			if(thumbnailImg != null && thumbnailImg.exists()) {
				thumbnailImg.delete();
			}
		}
	}

	private BufferedImage getThumbnailImage(BufferedImage image) {
		final int w = image.getWidth();
		final int h = image.getHeight();
		float ratio = (float) w / (float) h;
		int targetWidth = Math.min(MAX_THUMBNAIL_DIM, Math.max(w, h));
		int targetHeight = targetWidth;
		if (ratio < 1f) {
		    targetWidth = (int) ((float)targetHeight * ratio);
		} else {
		    targetHeight = (int) ((float)targetWidth / ratio);
		}
		BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
		final AffineTransform at = AffineTransform.getScaleInstance((float)targetWidth/w, (float)targetHeight/h);
		final AffineTransformOp ato = new AffineTransformOp(at, AffineTransformOp.TYPE_BICUBIC);
		return ato.filter(image, result);
	}

}
