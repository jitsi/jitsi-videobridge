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

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jivesoftware.smack.packet.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import static org.junit.Assert.*;

/**
 * Tests videobridge on conferences with raw UDP channels.
 *
 * @author Johannes Singler
 */
@RunWith(JUnit4.class)
public class RawUdpConferenceTest
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
        osgiHandler.start();

        bridge = osgiHandler.getService(Videobridge.class);
    }

    /**
     * Shutdown OSGi and the videobridge.
     */
    @AfterClass
    public static void tearDown()
        throws InterruptedException
    {
        osgiHandler.stop();
    }

    /**
     * Tests when requesting a raw UDP channel, we do not get candidates with
     * 127.0.0.1 IPs.
     *
     * Test fails only if the machine has only a loopback interface, which is
     * very unlikely.
     */
    @Test
    public void testNo_127_0_0_0_CandidateIps()
        throws Exception
    {
        Jid focusJid = JidCreate.from("focusJid");

        ColibriConferenceIQ confIq
            = ColibriUtilities.createConferenceIq(focusJid);
        confIq.getContents().get(0).getChannel(0).
                setTransport(new RawUdpTransportPacketExtension());

        IQ respIq = bridge.handleColibriConferenceIQ(confIq);

        assertTrue(respIq instanceof ColibriConferenceIQ);
        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        for (CandidatePacketExtension candidate :
                respConfIq.getContents().get(0).getChannel(0).
                        getTransport().getCandidateList())
        {
            assertNotEquals("127.0.0.1", candidate.getAttribute("ip"));
        }
    }

    /**
     * Tests requesting a raw UDP channel with RTP level relay type MIXER
     */
    @Test
    public void testMixerChannel()
            throws Exception
    {
        Jid focusJid = JidCreate.from("focusJid");

        ColibriConferenceIQ confIq
                = ColibriUtilities.createConferenceIq(focusJid);
        ColibriConferenceIQ.Channel channel
                = confIq.getContents().get(0).getChannel(0);
        channel.setTransport(new RawUdpTransportPacketExtension());
        channel.setRTPLevelRelayType(RTPLevelRelayType.MIXER);

        IQ respIq = bridge.handleColibriConferenceIQ(confIq);

        assertTrue(respIq instanceof ColibriConferenceIQ);
        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        assertEquals(RTPLevelRelayType.MIXER,
                        respConfIq.getContents().get(0).getChannel(0).
                            getRTPLevelRelayType());
    }
}
