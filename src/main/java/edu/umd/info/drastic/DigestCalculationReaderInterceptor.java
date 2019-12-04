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

import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.resteasy.spi.NoLogWebApplicationException;
import org.slf4j.Logger;

@Provider
public class DigestCalculationReaderInterceptor implements ReaderInterceptor {
    
    private static final Logger LOGGER = getLogger(DigestCalculationReaderInterceptor.class);
    public static final String DIGEST_ALGORITHM_PROPERTY = "digest.algorithm.prop";
    public static final String DIGEST_SUPPLIED_PROPERTY = "digest.supplied.prop";
    public static final String DIGEST_CALCULATED_PROPERTY = "digest.calculated.prop";

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
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
            LOGGER.info("Digest header uses unsupported algorithm: {}", context.getHeaders().getFirst("Digest"));
            throw new NoLogWebApplicationException(400);
    	}
    	
        // Set up digest calculator
        MessageDigest digest = null;
        if(!digests.isEmpty()) {
            digest = digests.keySet().stream().findAny().get(); // pick an algorithm
            DigestInputStream digestStream = new DigestInputStream(context.getInputStream(), digest);
            context.setInputStream(digestStream);
        }
        
        Object result = context.proceed();
        
        // compare and throw error if mismatched
        if(digest != null) {
            String calculated = Base64.encodeBase64String(digest.digest());
            String supplied = digests.get(digest);
            if(!calculated.equals(supplied)) {
        	LOGGER.debug("Computed {} digest value {} does not match supplied value {}", digest.getAlgorithm(), calculated, supplied);
        	throw new NoLogWebApplicationException(409);
            }
        }
	return result;
    }
}
