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
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.HttpMethod.POST;
import static javax.ws.rs.HttpMethod.PUT;
import static javax.ws.rs.core.HttpHeaders.LINK;
import static javax.ws.rs.core.Link.TYPE;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.util.List;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Link;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.trellisldp.vocabulary.LDP;

@Provider
public class MiscRequestFilter implements ContainerRequestFilter {

    private static final String EXC_URI = "http://fedora.info/definitions/fcrepo#ExternalContent";
    
    private static final Logger LOGGER = getLogger(MiscRequestFilter.class);

    @Override
    public void filter(final ContainerRequestContext req) throws IOException {
        if (nonNull(req.getMediaType())) {
            System.out.println("Content-Type: " + req.getMediaType());
        }
        if (PUT.equals(req.getMethod()) || POST.equals(req.getMethod())) {
            final List<Link> links = req.getHeaders().getOrDefault(LINK, emptyList()).stream().map(Link::valueOf)
                .collect(toList());
            // If there are multiple link headers and none are external content links,
            // keep only the first LDP type link.
            if (links.size() > 1 && !links.stream().anyMatch(MiscRequestFilter::isExternalContentLink)) {
                links.stream().filter(MiscRequestFilter::isLdpLink).map(Link::toString).findFirst()
                    .ifPresent(l -> req.getHeaders().putSingle(LINK, l));
            }
        }
    }

    private static Boolean isExternalContentLink(final Link link) {
        return TYPE.equals(link.getRel()) && EXC_URI.equals(link.getUri().toString());
    }

    private static Boolean isLdpLink(final Link link) {
        return TYPE.equals(link.getRel()) && link.getUri().toString().startsWith(LDP.getNamespace());
    }
}
