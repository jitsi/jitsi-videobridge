/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;
import org.ice4j.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.osgi.*;
import org.junit.*;
import org.junit.Test;
import org.junit.runner.*;
import org.junit.runners.*;
import org.osgi.framework.*;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Tests focus access control of the conference and various IQ processing
 * options.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class FocusControlTest
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
     * Initializes OSGi and the videobridge.
     */
    @BeforeClass
    public static void setUp()
        throws InterruptedException
    {
        StackProperties.initialize();

        OSGi.start(
            new BundleActivator()
            {
                @Override
                public void start(BundleContext bundleContext)
                    throws Exception
                {
                    FocusControlTest.bc = bundleContext;
                    synchronized (FocusControlTest.class)
                    {
                        FocusControlTest.class.notify();
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
     * Tests if the conference can be accessed only by the peer that has created
     * the conference.
     */
    @Test
    public void focusControlTest()
        throws Exception
    {
        String focusJid = "focusJid";

        ColibriConferenceIQ confIq = createConferenceIq(focusJid);
        ColibriConferenceIQ respIq;

        respIq = bridge.handleColibriConferenceIQ(confIq);

        assertNotNull(respIq);

        confIq.setID(respIq.getID());

        // Only focus can access this conference now
        confIq.setFrom("someOtherJid");
        respIq = bridge.handleColibriConferenceIQ(confIq);
        assertNull(respIq);

        // Expect NPE when no focus is provided with default options
        try
        {
            confIq.setFrom(null);
            bridge.handleColibriConferenceIQ(confIq);
            fail("No NPE thrown");
        }
        catch (NullPointerException npe)
        {
            // OK
        }
    }

    /**
     * Tests the behaviour when {@link Videobridge#OPTION_ALLOW_NO_FOCUS}
     * is being used.
     */
    @Test
    public void noFocusControlTest()
        throws Exception
    {
        ColibriConferenceIQ confIq = createConferenceIq(null);
        int options = Videobridge.OPTION_ALLOW_NO_FOCUS;
        ColibriConferenceIQ respIq;

        respIq = bridge.handleColibriConferenceIQ(confIq, options);
        assertNotNull(respIq);

        confIq.setID(respIq.getID());

        confIq.setFrom("someJid");
        respIq = bridge.handleColibriConferenceIQ(confIq, options);
        assertNotNull(respIq);
    }

    /**
     * Tests the behaviour when {@link Videobridge#OPTION_ALLOW_ANY_FOCUS}
     * is being used.
     */
    @Test
    public void anyFocusControlTest()
        throws Exception
    {
        String focusJid = "focusJid";

        ColibriConferenceIQ confIq = createConferenceIq(focusJid);
        int options = Videobridge.OPTION_ALLOW_ANY_FOCUS;
        ColibriConferenceIQ respIq;

        respIq = bridge.handleColibriConferenceIQ(confIq, options);

        assertNotNull(respIq);

        // Set conference id
        confIq.setID(respIq.getID());

        // Anyone can access the conference
        confIq.setFrom("someOtherJid");
        assertNotNull(bridge.handleColibriConferenceIQ(confIq, options));
        confIq.setFrom(null);
        assertNotNull(bridge.handleColibriConferenceIQ(confIq, options));

        options = Videobridge.OPTION_ALLOW_NO_FOCUS;
        confIq.setFrom(null);
        assertNotNull(bridge.handleColibriConferenceIQ(confIq, options));
        confIq.setFrom("focus3");
        assertNotNull(bridge.handleColibriConferenceIQ(confIq, options));
    }
}
