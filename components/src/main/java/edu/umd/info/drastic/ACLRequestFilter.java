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
import static java.util.function.Predicate.isEqual;
import static javax.ws.rs.Priorities.USER;
import static javax.ws.rs.core.HttpHeaders.LINK;
import static javax.ws.rs.core.Response.status;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;

import javax.annotation.Priority;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Link;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.trellisldp.vocabulary.LDP;
import org.trellisldp.vocabulary.Trellis;

@Provider
@Priority(USER)
public class ACLRequestFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = getLogger(ACLRequestFilter.class);
    
    /**
     * For use with RESTeasy and CDI proxies.
     *
     * @apiNote This construtor is used by CDI runtimes that require a public, no-argument constructor.
     *          It should not be invoked directly in user code.
     */
    public ACLRequestFilter() {
    }

    @Override
    public void filter(final ContainerRequestContext req) throws IOException {
        if (req.getHeaders().getOrDefault(LINK, emptyList()).stream().map(Link::valueOf).map(Link::getRel)
                .anyMatch(isEqual("acl"))) {
            req.abortWith(status(CONFLICT).link(Trellis.UnsupportedInteractionModel.getIRIString(),
                        LDP.constrainedBy.getIRIString()).build());
        }
    }
}
