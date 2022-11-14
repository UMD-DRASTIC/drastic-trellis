package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.getGraph;
import static edu.umd.info.drastic.NPSVocabulary.DRASTIC_AGENTS.crawler;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.List;

import javax.inject.Inject;

import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment.Strategy;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.slf4j.Logger;
import org.trellisldp.api.NotificationSerializationService;
import org.trellisldp.common.SimpleNotification;
import org.trellisldp.vocabulary.AS;
import org.trellisldp.vocabulary.LDP;
import org.trellisldp.vocabulary.PROV;
import org.trellisldp.vocabulary.SKOS;

import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;

/**
 * This LDP container crawler performs a breadth-first crawl of the containment tree, generating
 * custom messages for each child object. It may be invoked via a message to the crawl queue and
 * uses this same queue to recurse into child containers. 
 * 
 * Example message:
 * { 
 *   "startUri": "startUri",
 *   "depth": 10,
 *   "kafkaTopic": "objects",
 *   "options": {
 *     "key": "string value"
 *   }
 * }
 * 
 * @author jansen
 *
 */
public class KafkaContainerCrawler {
	private static final Logger LOGGER = getLogger(KafkaContainerCrawler.class);
	
	@Inject
	@Channel("crawler-out")
	Emitter<LDPCrawlRequest> emitter;
	
	@Inject
	NotificationSerializationService serializer;
	
	@Outgoing("null")
	@Incoming("crawler-in")
	@Acknowledgment(Strategy.PRE_PROCESSING)
	public Message<String> process(LDPCrawlRequest req) {
		LOGGER.info("Crawler got: {}", req.toString());
		if(req.depth > 0) {
			Graph g = getGraph(req.startUri);
			g.stream(null, LDP.contains, null).forEach(t -> {
				IRI contained = (IRI)t.getObject();
				LDPCrawlRequest childCrawl = new LDPCrawlRequest(contained.getIRIString(), req.depth - 1, req.kafkaTopic, req.options);
				emitter.send(childCrawl);
			});
		}
		OutgoingKafkaRecordMetadata<String> metadata = OutgoingKafkaRecordMetadata.<String> builder()
			        .withTopic(req.kafkaTopic)
			        .build();
		String message;
		if("objects".equals(req.kafkaTopic) || "new-binaries".equals(req.kafkaTopic)) {
			// serialize as ActivityStream
			SimpleNotification n = new SimpleNotification(req.startUri, crawler.iri, 
					List.of(PROV.Activity, AS.Update), 
					List.of(LDP.RDFSource, SKOS.Concept),
					"etag:123456");
			message = serializer.serialize(n);
		} else {
			message = req.startUri;
		}
		Message<String> result = Message.of(message).addMetadata(metadata);
		return result;
	}

}
