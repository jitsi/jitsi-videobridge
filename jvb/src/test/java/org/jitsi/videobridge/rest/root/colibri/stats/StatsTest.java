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

package org.jitsi.videobridge.rest.root.colibri.stats;

import org.eclipse.jetty.http.*;
import org.glassfish.jersey.server.*;
import org.glassfish.jersey.test.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;
import org.json.simple.parser.*;
import org.junit.*;

import javax.ws.rs.core.*;
import java.util.*;
import static junit.framework.TestCase.*;

import static org.mockito.Mockito.*;

public class StatsTest extends JerseyTest
{
    protected StatsManager statsManager;
    protected static final String BASE_URL = "colibri/stats";

    @Override
    protected Application configure()
    {
        statsManager = mock(StatsManager.class);

        enable(TestProperties.LOG_TRAFFIC);
        enable(TestProperties.DUMP_ENTITY);
        return new ResourceConfig() {
            {
                register(new MockBinder<>(statsManager, StatsManager.class));
                register(Stats.class);
            }
        };
    }

    @Test
    public void testGetStats() throws ParseException
    {
        Map<String, Object> fakeStats = new HashMap<>();
        fakeStats.put("stat1", "value1");
        fakeStats.put("stat2", "value2");
        VideobridgeStatistics videobridgeStatistics = mock(VideobridgeStatistics.class);
        when(videobridgeStatistics.getStats()).thenReturn(fakeStats);
        when(statsManager.getStatistics()).thenReturn(Collections.singletonList(videobridgeStatistics));

        Response resp = target(BASE_URL).request().get();
        assertEquals(HttpStatus.OK_200, resp.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

        JSONObject json = getJsonResult(resp);

        assertEquals("value1", json.get("stat1"));
        assertEquals("value2", json.get("stat2"));
    }

    @Test
    public void testGetStatsVideobridgeStatsNotFirst() throws ParseException
    {
        Map<String, Object> fakeStats = new HashMap<>();
        fakeStats.put("stat1", "value1");
        fakeStats.put("stat2", "value2");
        VideobridgeStatistics videobridgeStatistics = mock(VideobridgeStatistics.class);
        when(videobridgeStatistics.getStats()).thenReturn(fakeStats);

        DummyStats dummyStats = new DummyStats();

        List<Statistics> stats = new ArrayList<>();
        stats.add(dummyStats);
        stats.add(videobridgeStatistics);

        when(statsManager.getStatistics()).thenReturn(stats);

        Response resp = target(BASE_URL).request().get();
        assertEquals(HttpStatus.OK_200, resp.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

        JSONObject json = getJsonResult(resp);

        assertEquals("value1", json.get("stat1"));
        assertEquals("value2", json.get("stat2"));
    }

    @Test
    public void testGetStatsNoStatsPresent() throws ParseException
    {
        when(statsManager.getStatistics()).thenReturn(Collections.emptyList());

        Response resp = target(BASE_URL).request().get();
        assertEquals(HttpStatus.OK_200, resp.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

        JSONObject json = getJsonResult(resp);

        assertTrue(json.isEmpty());
    }

    private JSONObject getJsonResult(Response resp) throws ParseException
    {
        String responseBody = resp.readEntity(String.class);
        Object obj = new JSONParser().parse(responseBody);
        assertTrue("Stats response must be a JSON object", obj instanceof JSONObject);

        return (JSONObject)obj;
    }


    private static class DummyStats extends Statistics
    {
        @Override
        public void generate() { }
    }
}
