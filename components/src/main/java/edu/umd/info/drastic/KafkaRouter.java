package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.util.stream.StreamSupport;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.slf4j.Logger;
import org.trellisldp.vocabulary.LDP;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.smallrye.reactive.messaging.annotations.Broadcast;
import io.smallrye.reactive.messaging.kafka.Record;

/**
 * A {@link RouteBuilder} that forwards the Trellis object activity to Kafka.
 * <p>
 * Note that for the {@code @Inject} and {@code @ConfigProperty} annotations to work, this class has to be annotated
 * with {@code @ApplicationScoped}.
 */
@ApplicationScoped
public class KafkaRouter {
	
	private static final Logger LOGGER = getLogger(KafkaRouter.class);
	
	@Incoming("trellis")
	@Broadcast
	@Outgoing("broadcast")
	public String broadcast(String payload) {
		return payload;
	}
	
	@Incoming("broadcast")
    @Outgoing("objects-out")
    public Record<String, String> sendActivityStream(String payload) {
		JsonNode as;
		try {
			as = new ObjectMapper().readTree(payload);
		} catch (JsonProcessingException e) {
			LOGGER.error("cannot parse activitystream", e);
			return null;
		}
    	return Record.of(as.get("object").get("id").asText(), payload);
    }
	
	@Incoming("broadcast")
    @Outgoing("new-binaries-out")
    public Record<String, String> sendNewBinaries(String payload) {
		JsonNode as;
		try {
			as = new ObjectMapper().readTree(payload);
		} catch (JsonProcessingException e) {
			LOGGER.error("cannot parse activitystream", e);
			return null;
		}
		if(!StreamSupport.stream(((ArrayNode)as.at("/object/type")).spliterator(), false)
			.map(JsonNode::asText)
			.anyMatch(t -> { return LDP.NonRDFSource.getIRIString().equals(t); })) return null;
		if(!StreamSupport.stream(((ArrayNode)as.at("/type")).spliterator(), false)
			.map(JsonNode::asText)
			.anyMatch(t -> { return "Create".equals(t) || "Update".equals(t); })) return null;
		if(as.get("object").get("id").asText().contains("?")) return null;
		return Record.of(as.get("object").get("id").asText(), payload);
    }
}