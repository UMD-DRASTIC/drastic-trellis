package edu.umd.info.drastic;

import edu.umd.info.drastic.LDPCrawlRequest;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class CrawlDeserializer extends ObjectMapperDeserializer<LDPCrawlRequest> {
    public CrawlDeserializer() {
        super(LDPCrawlRequest.class);
    }
}
