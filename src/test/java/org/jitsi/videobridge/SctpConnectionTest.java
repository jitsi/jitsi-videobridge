package org.jitsi.videobridge;

import java.nio.*;
import java.util.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import org.jivesoftware.smack.packet.*;
import org.jitsi.sctp4j.*;
import org.junit.*;
import org.junit.runner.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;
import org.powermock.core.classloader.annotations.*;
import org.powermock.modules.junit4.*;

import static org.junit.Assert.*;
import static org.powermock.api.easymock.PowerMock.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest(SctpConnection.class)
public class SctpConnectionTest
{
    /**
     * Tested <tt>SctpConnection</tt> instance.
     */
    private SctpConnection sctpConnection;

    /**
     * Test fixtures.
     */

    private OSGiHandler osgiHandler = new OSGiHandler();

    /**
     * Initializes OSGi
     */
    @Before
    public void setUp()
        throws Exception
    {
        osgiHandler.start();
        Videobridge bridge = osgiHandler.getService(Videobridge.class);

        // Create the conference
        Jid focusJid = JidCreate.from("focusJid");
        ColibriConferenceIQ confIq
            = ColibriUtilities.createConferenceIq(focusJid);
        IQ respIq = bridge.handleColibriConferenceIQ(confIq);
        assertTrue(respIq instanceof ColibriConferenceIQ);
        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        // Find content and channel to run our SCTP connection alongside
        Content content = null;
        Channel channel = null;
        Conference conference = bridge.getConference(respConfIq.getID(), null);
        for (Content ct : conference.getContents()) {
            if (ct == null) {
                continue;
            }
            for (Channel cl : ct.getChannels()) {
                if (cl == null) {
                    continue;
                }
                content = ct;
                channel = cl;
                break;
            }
        }
        if (content == null || channel == null) {
            throw new Exception("Couldn't find candidate content or channel");
        }

        // Construct a SCTP connection
        String id = "sctp";
        String cb = "test";
        AbstractEndpoint endpoint = new Endpoint("endp", conference);
        sctpConnection =
            new SctpConnection(id, content, endpoint, 10000, cb, true);
    }

    /**
     * Shutdown OSGi
     */
    @After
    public void tearDown()
        throws InterruptedException
    {
        osgiHandler.stop();
    }

    /**
     * Fixture providing an SCTP_COMM_UP association change packet
     */
    private byte[] getCommunicationUpPacket()
    {
        // Data-structures from jitsi/libjitsi.git
        // commit: 70d104004a9bc9de79c22b73237e87a83570a2fb
        // path: /src/org/jitsi/sctp4j/SctpNotification.java
        int sz =
            (Short.SIZE * 2) +
            Integer.SIZE +
            (Short.SIZE * 4) +
            Integer.SIZE;

        return ByteBuffer.
            allocate(sz).
            order(ByteOrder.LITTLE_ENDIAN).
            putShort((short) SctpNotification.SCTP_ASSOC_CHANGE).
            putShort((short) 0).
            putInt(sz).
            putShort((short) SctpNotification.AssociationChange.SCTP_COMM_UP).
            putShort((short) 0).
            putShort((short) 0).
            putShort((short) 0).
            putInt(0).
            array();
    }

    /**
     * Ensure that parsing the COMM_UP packet succeeds.
     *
     * TODO(jayaddison): Upstream this into libjitsi; it's here as a
     * convenience due to the need for the fixture elsewhere in this file.
     */
    @Test
    public void testSctpPacketParsing()
    {
        byte[] data = getCommunicationUpPacket();
        SctpNotification notification = SctpNotification.parse(data);
        assertEquals(
            SctpNotification.SCTP_ASSOC_CHANGE,
            notification.sn_type
        );

        SctpNotification.AssociationChange association =
            (SctpNotification.AssociationChange) notification;
        assertEquals(
            SctpNotification.AssociationChange.SCTP_COMM_UP,
            association.state
        );
    }

    /**
     * Tests that connection-ready notifications are sent after a new
     * connection is accepted
     */
    @Test
    public void testConnectionReadyNotifications()
        throws Exception
    {
        SctpSocket sctpSocket = null;
        byte[] data = getCommunicationUpPacket();
        SctpNotification notification = SctpNotification.parse(data);

        // createPartialMock(SctpNotification.class, "notifySctpConnectionReady");
        // expectPrivate(sctpConnection, "notifySctpConnectionReady");

        sctpConnection.onSctpNotification(sctpSocket, notification);
    }
}
