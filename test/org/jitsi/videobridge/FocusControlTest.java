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
import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.osgi.*;
import org.jivesoftware.smack.packet.*;
import org.junit.*;
import org.junit.Test;
import org.junit.runner.*;
import org.junit.runners.*;
import org.osgi.framework.*;

import static org.junit.Assert.*;

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
     * Bundle activator used to start OSGi(stored for shutdown purpose)
     */
    private static BundleActivator activator;

    /**
     * Initializes OSGi and the videobridge.
     */
    @BeforeClass
    public static void setUp()
        throws InterruptedException
    {
        activator =
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
            };

        OSGi.start(activator);

        synchronized (FocusControlTest.class)
        {
            FocusControlTest.class.wait(5000);
            if(bc == null)
                throw new RuntimeException("Failed to start OSGI");
        }

        bridge = ServiceUtils.getService(bc, Videobridge.class);
    }

    /**
     * Shutdown OSGi and the videobridge.
     */
    @AfterClass
    public static void tearDown()
    {
        OSGi.stop(activator);
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

        ColibriConferenceIQ confIq
            = ColibriUtilities.createConferenceIq(focusJid);
        IQ respIq;

        respIq = bridge.handleColibriConferenceIQ(confIq);

        assertTrue(respIq instanceof ColibriConferenceIQ);

        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        confIq.setID(respConfIq.getID());

        // Only focus can access this conference now
        confIq.setFrom("someOtherJid");
        respIq = bridge.handleColibriConferenceIQ(confIq);
        assertNull(respIq);

        // Expect 'not_authorized' error when no focus is provided
        // with default options
        confIq.setFrom(null);
        IQ notAuthorizedError = bridge.handleColibriConferenceIQ(confIq);

        assertNotNull(notAuthorizedError);
        assertEquals(IQ.Type.ERROR, notAuthorizedError.getType());
        assertEquals(XMPPError.Condition.not_authorized.toString(),
                     notAuthorizedError.getError().getCondition());
    }

    /**
     * Tests the behaviour when {@link Videobridge#OPTION_ALLOW_NO_FOCUS}
     * is being used.
     */
    @Test
    public void noFocusControlTest()
        throws Exception
    {
        ColibriConferenceIQ confIq = ColibriUtilities.createConferenceIq(null);
        int options = Videobridge.OPTION_ALLOW_NO_FOCUS;
        IQ respIq;
        ColibriConferenceIQ respConfIq;

        respIq = bridge.handleColibriConferenceIQ(confIq, options);
        assertTrue(respIq instanceof ColibriConferenceIQ);

        respConfIq = (ColibriConferenceIQ) respIq;

        confIq.setID(respConfIq.getID());

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

        ColibriConferenceIQ confIq
            = ColibriUtilities.createConferenceIq(focusJid);
        int options = Videobridge.OPTION_ALLOW_ANY_FOCUS;
        IQ respIq;
        ColibriConferenceIQ respConfIq;

        respIq = bridge.handleColibriConferenceIQ(confIq, options);

        assertTrue(respIq instanceof ColibriConferenceIQ);

        respConfIq = (ColibriConferenceIQ) respIq;

        // Set conference id
        confIq.setID(respConfIq.getID());

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
