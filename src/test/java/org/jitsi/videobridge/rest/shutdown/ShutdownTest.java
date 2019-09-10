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

package org.jitsi.videobridge.rest.shutdown;

import org.eclipse.jetty.http.*;
import org.glassfish.jersey.servlet.*;
import org.glassfish.jersey.test.*;
import org.glassfish.jersey.test.grizzly.*;
import org.glassfish.jersey.test.spi.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.packet.*;
import org.junit.*;
import org.mockito.*;
import org.mockito.stubbing.*;

import javax.ws.rs.client.*;
import javax.ws.rs.core.*;

import static junit.framework.TestCase.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

public class ShutdownTest extends VideobridgeRestResourceTest
{
    private ArgumentCaptor<ShutdownIQ> shutdownIQArgumentCaptor = ArgumentCaptor.forClass(ShutdownIQ.class);

    @Override
    public Application getApplication()
    {
        throw new RuntimeException("This shouldn't be called for this test, see comments below");
    }

    // This method and configureDeployment are needed to set up a proper servlet context so that
    // the '@Context HttpServletRequest request' parameter in Shutdown#shutdown is set correctly
    @Override
    protected TestContainerFactory getTestContainerFactory() throws TestContainerException
    {
        return new GrizzlyWebTestContainerFactory();
    }

    @Override
    protected DeploymentContext configureDeployment()
    {
        return ServletDeploymentContext.forServlet(new ServletContainer(new ShutdownApp(videobridgeProvider))).build();
    }

    @Before
    public void beforeTest()
    {
        reset(videobridgeProvider, videobridge);
        when(videobridgeProvider.get()).thenReturn(videobridge);
    }

    private void setSuccessfulShutdownIqResponse()
    {
        when(videobridge.handleShutdownIQ(shutdownIQArgumentCaptor.capture()))
                .thenAnswer((Answer<IQ>) invocationOnMock -> IQ.createResultIQ(shutdownIQArgumentCaptor.getValue()));
    }

    private void setErrorShutdownIqResponse(XMPPError.Condition condition)
    {
        when(videobridge.handleShutdownIQ(shutdownIQArgumentCaptor.capture()))
                .thenAnswer((Answer<IQ>) invocationOnMock ->
                        IQ.createErrorResponse(shutdownIQArgumentCaptor.getValue(), condition));
    }

    @Test
    public void testGracefulShutdown()
    {
        setSuccessfulShutdownIqResponse();

        Shutdown.ShutdownJson json = new Shutdown.ShutdownJson(true);
        Response resp = target("/").request().post(Entity.json(json));

        assertTrue(shutdownIQArgumentCaptor.getValue().isGracefulShutdown());
        assertEquals("127.0.0.1", shutdownIQArgumentCaptor.getValue().getFrom().toString());
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testImmediateShutdown()
    {
        setSuccessfulShutdownIqResponse();

        Shutdown.ShutdownJson json = new Shutdown.ShutdownJson(false);
        Response resp = target("/").request().post(Entity.json(json));

        assertFalse(shutdownIQArgumentCaptor.getValue().isGracefulShutdown());
        assertEquals("127.0.0.1", shutdownIQArgumentCaptor.getValue().getFrom().toString());
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testXForwardedFor()
    {
        setSuccessfulShutdownIqResponse();

        Shutdown.ShutdownJson json = new Shutdown.ShutdownJson(false);
        Response resp = target("/")
                .request()
                .header("X-FORWARDED-FOR", "jitsi")
                .post(Entity.json(json));

        assertEquals("jitsi", shutdownIQArgumentCaptor.getValue().getFrom().toString());
        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testNotAuthorized()
    {
        setErrorShutdownIqResponse(XMPPError.Condition.not_authorized);

        Shutdown.ShutdownJson json = new Shutdown.ShutdownJson(false);
        Response resp = target("/").request().post(Entity.json(json));

        assertEquals(HttpStatus.UNAUTHORIZED_401, resp.getStatus());
    }

    @Test
    public void testServiceUnavailable()
    {
        setErrorShutdownIqResponse(XMPPError.Condition.service_unavailable);

        Shutdown.ShutdownJson json = new Shutdown.ShutdownJson(true);
        Response resp = target("/").request().post(Entity.json(json));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE_503, resp.getStatus());
    }

    @Test
    public void testOtherError()
    {
        setErrorShutdownIqResponse(XMPPError.Condition.undefined_condition);

        Shutdown.ShutdownJson json = new Shutdown.ShutdownJson(true);
        Response resp = target("/").request().post(Entity.json(json));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, resp.getStatus());
    }

    @Test
    public void testJsonString()
    {
        setSuccessfulShutdownIqResponse();

        String json = "{ \"graceful-shutdown\": \"true\" }";
        Response resp = target("/").request().post(Entity.json(json));

        assertEquals(HttpStatus.OK_200, resp.getStatus());
    }

    @Test
    public void testMissingField()
    {
        setSuccessfulShutdownIqResponse();

        String json = "{}";
        Response resp = target("/").request().post(Entity.json(json));

        assertEquals(HttpStatus.BAD_REQUEST_400, resp.getStatus());
    }
}
