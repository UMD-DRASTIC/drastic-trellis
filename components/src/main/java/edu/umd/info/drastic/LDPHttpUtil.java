package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.apache.commons.rdf.api.Graph;
import org.apache.http.client.utils.URIBuilder;
import org.apache.jena.commonsrdf.JenaCommonsRDF;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;

@ApplicationScoped
public class LDPHttpUtil {
	
	private static final Logger LOGGER = getLogger(LDPHttpUtil.class);

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
    
	static Graph getGraph(String iri) {
		try {
			HttpClient http = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(localhost(iri)).GET()
				.header("Prefer", "return=representation;")
				.header("Accept", "application/n-triples")
				.build();
			String body = http.send(req, BodyHandlers.ofString()).body();
			Model model = ModelFactory.createDefaultModel().read(IOUtils.toInputStream(body, "UTF-8"), iri,
					"N-TRIPLES");
			return JenaCommonsRDF.fromJena(model.getGraph());
		} catch (IOException | InterruptedException | URISyntaxException e) {
			LOGGER.error("Cannot get triples for {}", iri, e);
			throw new CompletionException(e);
		}
	}
	
	static void patchGraph(Graph graph, String location) {
	    String patch = "INSERT { "+ graph.toString() +" } WHERE {}";
		HttpResponse<Void> response;
		try {
			HttpClient http = HttpClient.newHttpClient();
			URI localDescLoc = localhost(location);
			response = http.send(HttpRequest.newBuilder(localDescLoc).method("PATCH", HttpRequest.BodyPublishers.ofString(patch))
				.header("Content-type", "application/sparql-update").build(), HttpResponse.BodyHandlers.discarding());
			if(response.statusCode() != 204) {
				LOGGER.error("Got a failure when patching binary description: {}", response.statusCode());
			}
		} catch (IOException | InterruptedException | URISyntaxException e) {
			LOGGER.error("Exception while patching graph {}\n{}", location, patch, e);
		}
	}
}
