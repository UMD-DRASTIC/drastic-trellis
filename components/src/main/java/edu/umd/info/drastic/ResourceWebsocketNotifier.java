package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.reactive.messaging.kafka.Record;

@ApplicationScoped
@ServerEndpoint("/notifier")
public class ResourceWebsocketNotifier {
	
	private static final Logger LOGGER = getLogger(ResourceWebsocketNotifier.class);

	// Map of IRI to the queue of sessions interested.
	private static ConcurrentMap<String, Queue<Session>> subscribers = new ConcurrentHashMap<>();
	
	@Inject @Channel("makePagedDocuments") Emitter<String> emitter;

	private ExecutorService executorService = Executors.newFixedThreadPool(4);

	@OnMessage
	public void onMessage(String msg, Session s) throws IOException {
		JsonNode as;
		try {
			as = new ObjectMapper().readTree(msg);
		} catch (JsonProcessingException e) {
			LOGGER.error("cannot parse activitystream", e);
			return;
		}
		if(as.has("makePagedDocuments")) {
			String id = as.at("/makePagedDocuments").asText();
			emitter.send(id);
			return;
		}
		String id = as.at("/subscribe").asText();
		// subscribe this session
		Queue<Session> sessions = subscribers.get(id);
		if (sessions == null) {
			sessions = new ArrayBlockingQueue<>(1000);
			Queue<Session> actual = subscribers.putIfAbsent(id, sessions);
			if(actual != null) {
				sessions = actual;
			}
		}
		if(!sessions.contains(s)) {
			sessions.add(s);
		}
	}
	
	@OnClose
	public void onClose(Session s) {
		for(Queue<Session> q : subscribers.values()) {
			q.remove(s);
		}
	}
	
	@Incoming("websocket")
    public void process(Record<String, String> record) {
		sendWebsocketUpdates(record.key(), record.value());
	}
	
	private CompletableFuture<Void> sendWebsocketUpdates(String key, String value) {
		return CompletableFuture.supplyAsync(() -> subscribers.get(key), executorService).thenAccept((sessions) -> {
				if(sessions == null) return;
				for(Session s : sessions) {
			    	if(s.isOpen()) {
			    		s.getAsyncRemote().sendText(value);
			    	}
			    }
			});
	}
}
