/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.util.*;

import org.junit.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
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
     * Tested <tt>Videobridge</tt> instance.
     */
    private static Videobridge bridge;

    private static OSGiHandler osgiHandler = new OSGiHandler();

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

        osgiHandler.start();

        bridge = osgiHandler.getService(Videobridge.class);
    }

    @AfterClass
    public static void tearDown()
        throws InterruptedException
    {
        osgiHandler.stop();

        bridge = null;
    }

    /**
     *
     * FIXME: add test case when unauthorized jid tries to shutdown
     */
    @Test
    public void testShutdown()
        throws Exception
    {
        TestShutdownRunnable testShutdownRunnable
            = new TestShutdownRunnable();

        bridge.setShutdownRunnable(testShutdownRunnable);

        Jid focusJid = JidCreate.from("focusJid");

        // Allocate one conference
        ColibriConferenceIQ confIq = ColibriUtilities
                .createConferenceIq(focusJid);
        IQ respIq;

        respIq = bridge.handleColibriConferenceIQ(confIq);

        assertTrue(respIq instanceof ColibriConferenceIQ);

        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        confIq.setID(respConfIq.getID());

        // Start the shutdown
        ShutdownIQ shutdownIQ = ShutdownIQ.createGracefulShutdownIQ();

        shutdownIQ.setFrom(focusJid);

        respIq = bridge.handleShutdownIQ(shutdownIQ);

        assertEquals(IQ.Type.result, respIq.getType());
        assertTrue(bridge.isShutdownInProgress());

        // Now send get conference state request
        respConfIq.setFrom(focusJid);
        respConfIq.setType(IQ.Type.get);
        respIq = bridge.handleColibriConferenceIQ(respConfIq);

        assertTrue(respIq instanceof ColibriConferenceIQ);

        respConfIq = (ColibriConferenceIQ) respIq;

        assertTrue(respConfIq.isGracefulShutdown());

        // Now send create new conference request and we expect error
        ColibriConferenceIQ createNewConfIq
                = ColibriUtilities.createConferenceIq(focusJid);

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
        respConfIq.setType(IQ.Type.set);

        bridge.handleColibriConferenceIQ(respConfIq);

        // There could be some channels due to health checks running
        // periodically, but they should be expired rather quickly.
        Util.waitForEquals(
            "Channels should be expired",
            0,
            () -> bridge.getChannelCount());

        if (bridge.getConferenceCount() > 0)
        {
            // There are no channels, but conference exist - expire them by hand
            Conference[] conferences = bridge.getConferences();
            for (Conference conf : conferences)
            {
                conf.expire();
            }
        }

        assertTrue(
            "The bridge should trigger a shutdown after last conference is "
                + "expired",
            testShutdownRunnable.shutdownStarted);
    }

    private void validateErrorResponse(IQ respIqRaw)
        throws Exception
    {
        XmlPullParserFactory factory
            = XmlPullParserFactory.newInstance();

        factory.setNamespaceAware(true);

        XmlPullParser parser = factory.newPullParser();

        String iqStr = respIqRaw.toXML().toString();

        parser.setInput(new StringReader(iqStr));

        parser.next();

        IQ respIq = PacketParserUtils.parseIQ(parser);

        assertEquals(IQ.Type.error, respIq.getType());

        XMPPError error = respIq.getError();
        assertNotNull(error);
        assertEquals(XMPPError.Condition.service_unavailable,
                error.getCondition());
        assertEquals(XMPPError.Type.CANCEL, error.getType());

        assertNotNull(
            error.getExtension(
                ColibriConferenceIQ.GracefulShutdown.ELEMENT_NAME,
                ColibriConferenceIQ.GracefulShutdown.NAMESPACE));
    }

    class TestShutdownRunnable
        implements Runnable
    {
        boolean shutdownStarted = false;

        @Override
        public void run()
        {
            shutdownStarted = true;
        }
    }
}
