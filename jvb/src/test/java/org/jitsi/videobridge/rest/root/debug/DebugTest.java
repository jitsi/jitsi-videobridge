/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
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

package org.jitsi.videobridge.rest.root.debug;

import org.eclipse.jetty.http.*;
import org.glassfish.hk2.utilities.binding.*;
import org.glassfish.jersey.server.*;
import org.glassfish.jersey.test.*;
import org.jitsi.health.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.annotations.*;
import org.junit.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;

import javax.ws.rs.*;
import javax.ws.rs.client.*;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.*;

import static junit.framework.TestCase.*;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class DebugTest extends JerseyTest
{
    protected Videobridge videobridge;
    protected static final String BASE_URL = "/debug";

    @Override
    protected Application configure()
    {
        videobridge = mock(Videobridge.class);

        Endpoint endpoint = mock(Endpoint.class);
        Conference conference = mock(Conference.class);
        when(videobridge.getConference("foo")).thenReturn(conference);
        when(conference.getEndpoint("bar")).thenReturn(endpoint);

        enable(TestProperties.LOG_TRAFFIC);
        enable(TestProperties.DUMP_ENTITY);
        return new ResourceConfig() {
            {
                register(new AbstractBinder()
                {
                    @Override
                    protected void configure()
                    {
                        bind(mock(HealthCheckServiceSupplier.class)).to(HealthCheckServiceSupplier.class);
                        bind(videobridge).to(Videobridge.class);
                    }
                });
                register(Debug.class);
            }
        };
    }

    @Test
    public void testAllResourcesAreBehindConfig()
    {
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage("org.jitsi.videobridge.rest.root.debug"))
                .filterInputsBy(new FilterBuilder().includePackage("org.jitsi.videobridge.rest.root.debug"))
                .setScanners(new SubTypesScanner(false), new TypeAnnotationsScanner()));

        for (Class<?> clazz : reflections.getTypesAnnotatedWith(Path.class))
        {
            assertTrue(
                    "Class " + clazz + " must be annotated with @EnabledByConfig",
                    clazz.isAnnotationPresent(EnabledByConfig.class));
            EnabledByConfig anno = clazz.getAnnotation(EnabledByConfig.class);
            assertEquals(
                    "Class " + clazz.getSimpleName() + " must be annotated with @EnabledByConfig(RestApis.debug)",
                    anno.value(),
                    RestApis.DEBUG);
        }
    }

    @Test
    public void testEnableDebugFeature()
    {
        Response resp = target(BASE_URL + "/features/jvb/" + DebugFeatures.PAYLOAD_VERIFICATION.getValue() + "/true")
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testEnableEndpointDebugFeature()
    {
        Response resp = target(BASE_URL + "/foo/bar/enable/" + EndpointDebugFeatures.PCAP_DUMP.getValue())
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testEnableNonexistentDebugFeature()
    {
        Response resp = target(BASE_URL + "/features/jvb/blah/true")
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.NOT_FOUND_404, resp.getStatus());
    }

    @Test
    public void testDisableDebugFeature()
    {
        Response resp = target(BASE_URL + "/features/jvb/" + DebugFeatures.PAYLOAD_VERIFICATION.getValue() + "/false")
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testDisableEndpointDebugFeature()
    {
        Response resp = target(BASE_URL + "/foo/bar/disable/" + EndpointDebugFeatures.PCAP_DUMP.getValue())
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testInvalidEndpointDebugFeature()
    {
        Response resp = target(BASE_URL + "/foo/broken/disable/" + EndpointDebugFeatures.PCAP_DUMP.getValue())
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.NOT_FOUND_404, resp.getStatus());
    }

    @Test
    public void testInvalidEndpointDebugFeatureState()
    {
        Response resp = target(BASE_URL + "/foo/bar/broken/" + EndpointDebugFeatures.PCAP_DUMP.getValue())
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, resp.getStatus());
    }

    @Test
    public void testDisableNonexistentDebugFeature()
    {
        Response resp = target(BASE_URL + "/features/jvb/blah/false")
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.NOT_FOUND_404, resp.getStatus());
    }
}
