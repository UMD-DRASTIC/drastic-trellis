package edu.umd.info.drastic;

import static org.apache.camel.model.dataformat.JsonLibrary.Jackson;
import static org.slf4j.LoggerFactory.getLogger;
import static org.trellisldp.camel.ActivityStreamProcessor.ACTIVITY_STREAM_OBJECT_ID;

import javax.enterprise.context.ApplicationScoped;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.slf4j.Logger;
import org.trellisldp.camel.ActivityStreamProcessor;

/**
 * A {@link RouteBuilder} that forwards the Trellis object activity to Kafka.
 * <p>
 * Note that for the {@code @Inject} and {@code @ConfigProperty} annotations to work, this class has to be annotated
 * with {@code @ApplicationScoped}.
 */
@ApplicationScoped
public class KafkaRouter extends RouteBuilder {

	private static final Logger LOGGER = getLogger(KafkaRouter.class);
	
    @Override
    public void configure() throws Exception {
    	from("seda:trellis").routeId("KafkaRouter")
        .unmarshal().json(Jackson)
        .process(new ActivityStreamProcessor())
        .filter(header(ACTIVITY_STREAM_OBJECT_ID).isNotNull())
        	.setHeader(KafkaConstants.KEY, constant("Trellis")) // Key of the message
        	.to("kafka:objects?brokers=${properties:kafka.address}");
    	LOGGER.info("KafkaRouter configured");
    }
}