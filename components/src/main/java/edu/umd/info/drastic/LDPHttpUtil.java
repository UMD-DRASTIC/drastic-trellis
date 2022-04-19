package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.http.client.utils.URIBuilder;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;

@ApplicationScoped
public class LDPHttpUtil {
	
	@SuppressWarnings("unused")
	private static final Logger LOGGER = getLogger(AccessImageProcessor.class);

    @Inject
    @ConfigProperty(name = "trellis.client-username", defaultValue = "")
    String username;
    
    @Inject
    @ConfigProperty(name = "trellis.client-password", defaultValue = "")
    String password;
    
    static Integer localPort = ConfigProvider.getConfig().getValue("quarkus.http.port", Integer.class);
    
    HttpClient createanAuthenticatingHttpClient() {
    	return HttpClient.newBuilder()
    		.connectTimeout(Duration.ofSeconds(5))
    		.authenticator(
    			new Authenticator() {
    				@Override
					protected PasswordAuthentication getPasswordAuthentication() {
    					return new PasswordAuthentication(username, password.toCharArray());
					}
    			})
        	.version(HttpClient.Version.HTTP_1_1)
        	.build();
    }
    
    static URI localhost(String uri) throws URISyntaxException {
		return new URIBuilder(uri)
		.setHost("localhost")
		.setScheme("http")
		.setPort(localPort).build();
    }
    
    static URI localhost(URI uri) throws URISyntaxException {
		return new URIBuilder(uri)
		.setHost("localhost")
		.setScheme("http")
		.setPort(localPort).build();
    }
}
