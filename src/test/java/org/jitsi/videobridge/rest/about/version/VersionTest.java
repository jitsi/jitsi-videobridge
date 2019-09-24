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

package org.jitsi.videobridge.rest.about.version;

import org.eclipse.jetty.http.*;
import org.glassfish.jersey.server.*;
import org.glassfish.jersey.test.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.util.*;
import org.junit.*;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.*;

import static junit.framework.TestCase.*;
import static org.mockito.Mockito.*;

public class VersionTest extends JerseyTest
{
    protected VersionServiceProvider versionServiceProvider;
    protected VersionService versionService;
    protected static final String BASE_URL = "/about/version";

    @Override
    protected Application configure()
    {
        versionServiceProvider = mock(VersionServiceProvider.class);
        versionService = mock(VersionService.class);
        when(versionServiceProvider.get()).thenReturn(versionService);

        enable(TestProperties.LOG_TRAFFIC);
        enable(TestProperties.DUMP_ENTITY);
        return new ResourceConfig() {
            {
                register(new MockBinder<>(versionServiceProvider, VersionServiceProvider.class));
                register(Version.class);
            }
        };
    }

    @Test
    public void test()
    {
        when(versionService.getCurrentVersion()).thenReturn(
            new VersionImpl("appName", 2, 0)
        );

        Response resp = target(BASE_URL).request().get();
        assertEquals(HttpStatus.OK_200, resp.getStatus());
        Version.VersionInfo versionInfo = resp.readEntity(Version.VersionInfo.class);
        assertEquals("appName", versionInfo.name);
        assertEquals("2.0", versionInfo.version);
    }
}