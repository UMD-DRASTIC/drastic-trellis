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

import static java.util.Objects.nonNull;
import static javax.ws.rs.HttpMethod.GET;
import static javax.ws.rs.HttpMethod.HEAD;
import static javax.ws.rs.core.HttpHeaders.LINK;
import static javax.ws.rs.core.Link.TYPE;
import static javax.ws.rs.core.Link.fromUri;
import static org.slf4j.LoggerFactory.getLogger;
import static org.trellisldp.http.core.HttpConstants.EXT;
import static org.trellisldp.http.core.HttpConstants.MEMENTO_DATETIME;
import static org.trellisldp.http.core.HttpConstants.TIMEMAP;

import java.io.IOException;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.trellisldp.vocabulary.Memento;

@Provider
public class MementoResponseFilter implements ContainerResponseFilter {

    private static final Logger LOGGER = getLogger(MementoResponseFilter.class);
    
    @Override
    public void filter(final ContainerRequestContext req, final ContainerResponseContext res) throws IOException {
	LOGGER.debug("running");
        if (GET.equals(req.getMethod()) || HEAD.equals(req.getMethod())) {
            // Mementos
            if (nonNull(res.getHeaderString(MEMENTO_DATETIME))) {
                res.getHeaders().add(LINK, fromUri(Memento.Memento.getIRIString()).rel(TYPE).build());
            // TimeMaps
            } else if (TIMEMAP.equals(req.getUriInfo().getQueryParameters().getFirst(EXT))) {
                res.getHeaders().add(LINK, fromUri(Memento.TimeMap.getIRIString()).rel(TYPE).build());
                //res.getHeaders().add(LINK, fromUri(LDP.Container.getIRIString()).rel(TYPE).build());
            // Original resources
            } else {
                res.getHeaders().add(LINK, fromUri(Memento.OriginalResource.getIRIString()).rel(TYPE).build());
                res.getHeaders().add(LINK, fromUri(Memento.TimeGate.getIRIString()).rel(TYPE).build());
            }
        }
    }
}
