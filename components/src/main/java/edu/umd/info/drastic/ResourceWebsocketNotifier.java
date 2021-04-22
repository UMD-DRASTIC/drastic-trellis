package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.enterprise.context.Dependent;
import javax.websocket.OnMessage;
import javax.websocket.OnClose; 
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;

import io.smallrye.reactive.messaging.kafka.Record;

@Dependent
@ServerEndpoint("/notifier")
public class ResourceWebsocketNotifier {
	
	private static final Logger LOGGER = getLogger(ResourceWebsocketNotifier.class);

	// Map of IRI to the queue of sessions interested.
	private static ConcurrentMap<String, Queue<Session>> listeners = new ConcurrentHashMap<>();

	@OnMessage
	public void onMessage(String id, Session s) throws IOException {
		LOGGER.debug("websocket client msg: {}", id);
		// subscribe this session
		Queue<Session> sessions = listeners.get(id);
		if (sessions == null) {
			sessions = new ArrayBlockingQueue<>(1000);
			Queue<Session> actual = listeners.putIfAbsent(id, sessions);
			if(actual != null) {
				sessions = actual;
			}
		}
		sessions.add(s);
	}
	
	@OnClose
	public void onClose(Session s) {
		for(Queue<Session> q : listeners.values()) {
			q.remove(s);
		}
	}
	
	@Incoming("websocket")
    public void process(Record<String, String> record) {
		LOGGER.debug("notifying for broadcast of key: {}", record.key());
		Queue<Session> sessions = listeners.get(record.key());
		if (sessions != null) {
		    for(Session s : sessions) {
		    	if(s.isOpen()) {
		    		try {
		    			LOGGER.debug("sending activity to websocket: {}", record.value());
		    		    s.getBasicRemote().sendText(record.value());
		    		} catch (IOException e) {
		    		    e.printStackTrace();
		    		}
		    	}
		    }
		}
	}
}
