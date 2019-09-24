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

package org.jitsi.videobridge.rest.debug;

import org.eclipse.jetty.http.*;
import org.glassfish.jersey.server.*;
import org.glassfish.jersey.test.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.util.*;
import org.junit.*;

import javax.ws.rs.client.*;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.*;

import static junit.framework.TestCase.*;
import static org.mockito.Mockito.*;

public class DebugTest extends JerseyTest
{
    protected VideobridgeProvider videobridgeProvider;
    protected Videobridge videobridge;
    protected static final String BASE_URL = "/colibri/debug";

    @Override
    protected Application configure()
    {
        videobridgeProvider = mock(VideobridgeProvider.class);
        videobridge = mock(Videobridge.class);
        when(videobridgeProvider.get()).thenReturn(videobridge);

        enable(TestProperties.LOG_TRAFFIC);
        enable(TestProperties.DUMP_ENTITY);
        return new ResourceConfig() {
            {
                register(new MockBinder<>(videobridgeProvider, VideobridgeProvider.class));
                register(Debug.class);
            }
        };
    }

    @Test
    public void testEnableDebugFeature()
    {
        Response resp = target("/colibri/debug/enable/" + DebugFeatures.PAYLOAD_VERIFICATION.getValue())
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testEnableNonexistentDebugFeature()
    {
        Response resp = target(BASE_URL + "/enable/blah")
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.NOT_FOUND_404, resp.getStatus());
    }

    @Test
    public void testDisableDebugFeature()
    {
        Response resp = target(BASE_URL + "/disable/" + DebugFeatures.PAYLOAD_VERIFICATION.getValue())
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testDisableNonexistentDebugFeature()
    {
        Response resp = target(BASE_URL + "/disable/blah")
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.NOT_FOUND_404, resp.getStatus());
    }
}
