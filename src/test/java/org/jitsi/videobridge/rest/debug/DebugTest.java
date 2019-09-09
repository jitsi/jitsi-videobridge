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
import org.jitsi.videobridge.rest.*;
import org.junit.*;

import javax.ws.rs.client.*;
import javax.ws.rs.core.*;

import static junit.framework.TestCase.*;
import static org.mockito.Mockito.*;

public class DebugTest extends VideobridgeRestResourceTest
{
    @Override
    protected Application getApplication()
    {
        return new DebugApp(videobridgeProvider);
    }

    @Before
    public void beforeTest()
    {
        when(videobridgeProvider.get()).thenReturn(videobridge);
    }

    @Test
    public void testEnableDebugFeature()
    {
        Response resp = target("/debug/enable/" + DebugFeatures.PAYLOAD_VERIFICATION.getValue())
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testEnableNonexistentDebugFeature()
    {
        Response resp = target("/debug/enable/blah")
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.NOT_FOUND_404, resp.getStatus());
    }

    @Test
    public void testDisableDebugFeature()
    {
        Response resp = target("/debug/disable/" + DebugFeatures.PAYLOAD_VERIFICATION.getValue())
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testDisableNonexistentDebugFeature()
    {
        Response resp = target("/debug/disable/blah")
                .request()
                .post(Entity.json(null));
        assertEquals(HttpStatus.NOT_FOUND_404, resp.getStatus());
    }
}