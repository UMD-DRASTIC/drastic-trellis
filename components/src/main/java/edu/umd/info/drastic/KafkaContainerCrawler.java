package edu.umd.info.drastic;

import static edu.umd.info.drastic.LDPHttpUtil.getGraph;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.Map;

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
import org.trellisldp.vocabulary.LDP;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;

/**
 * This LDP container crawler performs a breadth-first crawl of the containment tree, generating
 * custom messages for each child object. It may be invoked via a message to the crawl queue and
 * uses this same queue to recurse into child containers. 
 * @author jansen
 *
 */
public class KafkaContainerCrawler {
	private static final Logger LOGGER = getLogger(KafkaContainerCrawler.class);
	
	@Inject
	@Channel("crawler-out")
	Emitter<LDPCrawlRequest> emitter;
	
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
		Map<String, String> msg = req.options;
		msg.put("iri", req.startUri);
		String message;
		try {
			message = new ObjectMapper().writer().writeValueAsString(msg);
		} catch (JsonProcessingException e) {
			LOGGER.error("Error while encoding json", e);
			return null;
		}
		Message<String> result = Message.of(message).addMetadata(metadata);
		return result;
	}

}
