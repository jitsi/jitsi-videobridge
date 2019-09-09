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

package org.jitsi.videobridge.rest.health;

import org.eclipse.jetty.http.*;
import org.jitsi.videobridge.rest.*;
import org.junit.*;

import javax.ws.rs.core.*;

import static junit.framework.TestCase.*;
import static org.mockito.Mockito.*;

public class HealthTest extends VideobridgeRestResourceTest
{

    @Override
    public Application getApplication()
    {
        return new HealthApp(videobridgeProvider);
    }

    @Before
    public void beforeTest()
    {
        when(videobridgeProvider.get()).thenReturn(videobridge);
    }

    @Test
    public void testHealthNoException() throws Exception
    {
        doNothing().when(videobridge).healthCheck();

        Response resp = target("/health").request().get();
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testHealthException() throws Exception
    {
        doThrow(new RuntimeException("")).when(videobridge).healthCheck();

        Response resp = target("/health").request().get();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, resp.getStatus());
    }
}