/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.service.shutdown.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.osgi.*;

import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.util.*;

import org.junit.*;

import org.osgi.framework.*;

import org.xmlpull.v1.*;

import java.io.*;

import static org.junit.Assert.*;

/**
 * Tests for bridge graceful shutdown functionality.
 *
 * @author Pawel Domas
 */
public class BridgeShutdownTest
{
    /**
     * OSGi bundle context instance.
     */
    private static BundleContext bc;

    /**
     * Tested <tt>Videobridge</tt> instance.
     */
    private static Videobridge bridge;

    /**
     * Initializes OSGi and the videobridge.
     */
    @BeforeClass
    public static void setUp()
        throws InterruptedException
    {
        // Allow focus JID
        System.setProperty(
            Videobridge.SHUTDOWN_ALLOWED_SOURCE_REGEXP_PNAME,
            "focus.*");

        OSGi.start(
            new BundleActivator()
            {
                @Override
                public void start(BundleContext bundleContext)
                    throws Exception
                {
                    BridgeShutdownTest.bc = bundleContext;
                    synchronized (BridgeShutdownTest.class)
                    {
                        BridgeShutdownTest.class.notify();
                    }
                }

                @Override
                public void stop(BundleContext bundleContext)
                    throws Exception
                {

                }
            }
        );

        synchronized (FocusControlTest.class)
        {
            FocusControlTest.class.wait(5000);
            if(bc == null)
                throw new RuntimeException("Failed to start OSGI");
        }

        bridge = ServiceUtils.getService(bc, Videobridge.class);
    }

    /**
     * Creates {@link ColibriConferenceIQ} with audio content and empty channel
     * IQ. Conference ID is empty hence it can be used to created new conference
     * on the bridge.
     *
     * @param focusJid conference focus owner.
     *
     * @return {@link ColibriConferenceIQ} with audio content and empty channel
     *         IQ.
     */
    private ColibriConferenceIQ createConferenceIq(String focusJid)
    {
        ColibriConferenceIQ confIq = new ColibriConferenceIQ();

        confIq.setFrom(focusJid);

        ColibriConferenceIQ.Content audioContent
            = new ColibriConferenceIQ.Content(MediaType.AUDIO.toString());

        ColibriConferenceIQ.Channel channel
            = new ColibriConferenceIQ.Channel();

        audioContent.addChannel(channel);

        confIq.addContent(audioContent);

        return confIq;
    }

    /**
     *
     * FIXME: add test case when unauthorized jid tries to shutdown
     */
    @Test
    public void testShutdown()
        throws Exception
    {
        TestShutdownService testShutdownService
            = new TestShutdownService();

        bridge.getBundleContext().registerService(
            ShutdownService.class,
            testShutdownService, null);

        String focusJid = "focusJid";

        // Allocate one conference
        ColibriConferenceIQ confIq = createConferenceIq(focusJid);
        IQ respIq;

        respIq = bridge.handleColibriConferenceIQ(confIq);

        assertTrue(respIq instanceof ColibriConferenceIQ);

        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        confIq.setID(respConfIq.getID());

        // Start the shutdown
        GracefulShutdownIQ shutdownIQ = new GracefulShutdownIQ();

        shutdownIQ.setFrom(focusJid);

        respIq = bridge.handleGracefulShutdownIQ(shutdownIQ);

        assertEquals(IQ.Type.RESULT, respIq.getType());
        assertTrue(bridge.isShutdownInProgress());

        // Now send get conference state request
        respConfIq.setFrom(focusJid);
        respConfIq.setType(IQ.Type.GET);
        respIq = bridge.handleColibriConferenceIQ(respConfIq);

        assertTrue(respIq instanceof ColibriConferenceIQ);

        respConfIq = (ColibriConferenceIQ) respIq;

        assertTrue(respConfIq.isGracefulShutdown());

        // Now send create new conference request and we expect error
        ColibriConferenceIQ createNewConfIq = createConferenceIq(focusJid);

        respIq = bridge.handleColibriConferenceIQ(createNewConfIq);

        validateErrorResponse(respIq);

        // FIXME use utility function or class to create colibri IQs
        // Ok we can't create new conferences, so let's expire the last one
        for (ColibriConferenceIQ.Content content
                : respConfIq.getContents())
        {
            for (ColibriConferenceIQ.Channel channel
                    : content.getChannels())
            {
                channel.setExpire(0);
            }
            for (ColibriConferenceIQ.SctpConnection connection
                    : content.getSctpConnections())
            {
                connection.setExpire(0);
            }
        }

        respConfIq.setFrom(focusJid);
        respConfIq.setType(IQ.Type.SET);

        respIq = bridge.handleColibriConferenceIQ(respConfIq);

        // Channels should be expired
        assertEquals(0, bridge.getChannelCount());

        if (bridge.getConferenceCount() > 0)
        {
            // There are no channels, but conference exist - expire them by hand
            Conference[] conferences = bridge.getConferences();
            for (Conference conf : conferences)
            {
                conf.expire();
            }
        }

        // The bridge should trigger a shutdown after last conference is expired
        assertTrue(testShutdownService.shutdownStarted);
    }

    private void validateErrorResponse(IQ respIqRaw)
        throws Exception
    {
        XmlPullParserFactory factory
            = XmlPullParserFactory.newInstance();

        factory.setNamespaceAware(true);

        XmlPullParser parser = factory.newPullParser();

        String iqStr = respIqRaw.toXML();

        parser.setInput(new StringReader(iqStr));

        parser.next();

        IQ respIq = PacketParserUtils.parseIQ(parser, null);

        assertEquals(IQ.Type.ERROR, respIq.getType());

        XMPPError error = respIq.getError();
        assertNotNull(error);
        assertEquals(XMPPError.Type.CANCEL, error.getType());

        assertNotNull(
            error.getExtension(
                ColibriConferenceIQ.GracefulShutdown.ELEMENT_NAME,
                ColibriConferenceIQ.GracefulShutdown.NAMESPACE));
    }

    class TestShutdownService
        implements ShutdownService
    {
        boolean shutdownStarted = false;

        @Override
        public void beginShutdown()
        {
            shutdownStarted = true;
        }
    }
}
