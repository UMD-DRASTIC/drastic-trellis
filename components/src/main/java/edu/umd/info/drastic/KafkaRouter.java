package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.slf4j.Logger;

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
    @Outgoing("objects-out")
    public String sendKafka(String payload) throws Exception {
		LOGGER.info("got a trellis:" + payload);
    	return payload;
    }
}