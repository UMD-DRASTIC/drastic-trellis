/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.umd.info.drastic;

import static java.util.Collections.emptyList;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;

@Provider
public class DigestCheckRequestFilter implements ContainerRequestFilter {
    
    private static final Logger LOGGER = getLogger(DigestCheckRequestFilter.class);

    @Override
    public void filter(ContainerRequestContext context) throws IOException, WebApplicationException {
	// Fedora Specification 1.0, section 3.5.1 and 3.6.2:
	//   An HTTP POST request that would create an LDP-NR and includes a Digest header (as described in [RFC3230])
	//   for which the instance-digest in that header does not match that of the new LDP-NR MUST be rejected with 
	//   a 409 (Conflict) response.
	//   An HTTP POST request that includes an unsupported Digest algorithm (as described in [RFC3230]), SHOULD
	//   be rejected with a 400 (Bad Request) response.
        // Header format is as follows:
        // 	Digest: md5=HUXZLQLMuI/KZ5KDcJPcOA==
        // 	Digest: SHA=thvDyvhfIqlvFe+A9MYgxAfm1q5=,unixsum=30637   
	LOGGER.debug("running");
        // Get all the supplied digests that we implement
        Map<MessageDigest, String> digests = context.getHeaders().getOrDefault("Digest", emptyList()).stream()
        	.flatMap( digest -> { return Stream.of(digest.split(",")); })
        	.<String[]>map( digest -> { return digest.trim().split("=", 2); } )
        	.filter( dig -> {
        	    try {
        		DigestUtils.getDigest(dig[0]);
        		return true;
        	    } catch(Exception e) { return false; }
        	})
        	.collect( Collectors.toMap(
        		dig -> { return DigestUtils.getDigest(dig[0]); }, 
                    dig -> { return dig[1]; } ));
        	
        // Fail quickly if algorithm unsupported
        if(digests.isEmpty() && context.getHeaders().containsKey("Digest")) {
            String msg = MessageFormat.format("Digest header uses unsupported algorithm: {0}", context.getHeaders().getFirst("Digest"));
            LOGGER.info(msg);
            context.abortWith(Response.status(400, msg).build());
            return;
    	}
    	
        // Calculate digest by streaming to temporary file.
        MessageDigest digest = null;
        if(!digests.isEmpty()) {
            File tmp = File.createTempFile("digestedUpload-", ".dat");
            digest = digests.keySet().stream().findAny().get(); // pick an algorithm
            String supplied = digests.get(digest);
            DigestInputStream digestStream = new DigestInputStream(context.getEntityStream(), digest);
            byte[] buffer = new byte[8 * 1024];
            int bytesRead;
            try(FileOutputStream fos = new FileOutputStream(tmp)) {
                while ((bytesRead = digestStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            String calculated = Base64.encodeBase64String(digestStream.getMessageDigest().digest());
            if(!calculated.equals(supplied)) {
        	String msg = MessageFormat.format("Upload rejected, computed {0} digest value {1} does not match supplied value {2}",
        		digest.getAlgorithm(), calculated, supplied);
        	LOGGER.info(msg);
                context.abortWith(Response.status(409, msg).build());
            } else {
                InputStream is = Files.newInputStream(tmp.toPath(), StandardOpenOption.DELETE_ON_CLOSE);
                context.setEntityStream(is);
            }
        }
    }

}
