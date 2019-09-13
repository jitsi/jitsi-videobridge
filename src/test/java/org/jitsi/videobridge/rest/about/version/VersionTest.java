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
import org.glassfish.jersey.test.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.util.*;
import org.junit.*;

import javax.ws.rs.core.*;

import static junit.framework.TestCase.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VersionTest extends JerseyTest
{
    protected static VersionServiceProvider versionServiceProvider;
    protected static VersionService versionService;

    @BeforeClass
    public static void setup()
    {
        versionServiceProvider = mock(VersionServiceProvider.class);
        versionService = mock(VersionService.class);
    }

    @Override
    protected Application configure()
    {
        enable(TestProperties.LOG_TRAFFIC);
        enable(TestProperties.DUMP_ENTITY);
        return new VersionApp(versionServiceProvider);
    }

    @Before
    public void beforeTest()
    {
        when(versionServiceProvider.get()).thenReturn(versionService);
    }

    @Test
    public void test()
    {
        when(versionService.getCurrentVersion()).thenReturn(
            new VersionImpl("appName", 2, 0)
        );

        Response resp = target("/version").request().get();
        assertEquals(HttpStatus.OK_200, resp.getStatus());
        Version.VersionInfo versionInfo = resp.readEntity(Version.VersionInfo.class);
        assertEquals("appName", versionInfo.name);
        assertEquals("2.0", versionInfo.version);
    }
}