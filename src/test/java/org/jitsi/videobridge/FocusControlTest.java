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

import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.util.config.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.health.*;
import org.jivesoftware.smack.packet.*;
import org.junit.*;
import org.junit.Test;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

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
     * Tested <tt>Videobridge</tt> instance.
     */
    private static Videobridge bridge;

    private static OSGiHandler osgiHandler = new OSGiHandler();

    private static Logger logger
            = new LoggerImpl(FocusControlTest.class.getName());

    /**
     * Initializes OSGi and the videobridge.
     */
    @BeforeClass
    public static void setUp()
        throws InterruptedException
    {
        JvbConfig.init();

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

    private static void expectResult(
            ColibriConferenceIQ confIq,
            int processingOptions)
        throws Exception
    {
        IQ respIq = bridge.handleColibriConferenceIQ(confIq, processingOptions);

        assertEquals(IQ.Type.result, respIq.getType());
        assertTrue(respIq instanceof ColibriConferenceIQ);
    }

    private static void expectNotAuthorized(ColibriConferenceIQ confIq,
                                            int processingOptions)
        throws Exception
    {
        IQ respIq = bridge.handleColibriConferenceIQ(confIq, processingOptions);

        logger.info(respIq.toXML());

        assertNotNull(respIq);
        assertEquals(IQ.Type.error, respIq.getType());
        assertEquals(
            XMPPError.Condition.not_authorized,
            respIq.getError().getCondition());
    }

    private static void expectNotAuthorized(HealthCheckIQ healthIq)
        throws Exception
    {
        IQ respIq = bridge.handleHealthCheckIQ(healthIq);

        logger.info(respIq.toXML());

        assertNotNull(respIq);
        assertEquals(IQ.Type.error, respIq.getType());
        assertEquals(
            XMPPError.Condition.not_authorized,
            respIq.getError().getCondition());
    }

    /**
     * Tests if the conference can be accessed only by the peer that has created
     * the conference.
     */
    @Test
    public void focusControlTest()
        throws Exception
    {
        Jid focusJid = JidCreate.from("focusJid");
        int options = 0;

        ColibriConferenceIQ confIq
            = ColibriUtilities.createConferenceIq(focusJid);
        IQ respIq = bridge.handleColibriConferenceIQ(confIq);

        assertTrue(respIq instanceof ColibriConferenceIQ);

        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        confIq.setID(respConfIq.getID());

        // Only focus can access this conference now
        confIq.setFrom(JidCreate.from("someOtherJid"));
        respIq = bridge.handleColibriConferenceIQ(confIq);
        assertNotNull(respIq);
        XMPPError error = respIq.getError();
        //TODO(brian): this test fails, because we no longer hit the access control in Videobridge#getConference which
        // would set the error (we instead get the conference from the ColibriShim.ConferenceShim which stores a reference
        // directly.  fix that one way or another.
        assertNotNull(error);
        // Should be 'not-allowed', but not easy to distinguish between
        // "conference not found"and "invalid focus" errors in the Videobridge
        // class without more refactoring
        assertEquals(
            XMPPError.Condition.bad_request, error.getCondition());

        // Expect 'not_authorized' error when no focus is provided
        // with default options
        confIq.setFrom((Jid)null);

        expectNotAuthorized(confIq, options);
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

        IQ respIq = bridge.handleColibriConferenceIQ(confIq, options);
        assertTrue(respIq instanceof ColibriConferenceIQ);

        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        confIq.setID(respConfIq.getID());

        confIq.setFrom(JidCreate.from("someJid"));
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
        Jid focusJid = JidCreate.from("focusJid");

        ColibriConferenceIQ confIq
            = ColibriUtilities.createConferenceIq(focusJid);
        int options = Videobridge.OPTION_ALLOW_ANY_FOCUS;

        IQ respIq = bridge.handleColibriConferenceIQ(confIq, options);

        assertTrue(respIq instanceof ColibriConferenceIQ);

        ColibriConferenceIQ respConfIq = (ColibriConferenceIQ) respIq;

        // Set conference id
        confIq.setID(respConfIq.getID());

        // Anyone can access the conference
        confIq.setFrom(JidCreate.from("someOtherJid"));
        assertNotNull(bridge.handleColibriConferenceIQ(confIq, options));
        confIq.setFrom((Jid)null);
        assertNotNull(bridge.handleColibriConferenceIQ(confIq, options));

        options = Videobridge.OPTION_ALLOW_NO_FOCUS;
        confIq.setFrom((Jid)null);
        assertNotNull(bridge.handleColibriConferenceIQ(confIq, options));
        confIq.setFrom(JidCreate.from("focus3"));
        assertNotNull(bridge.handleColibriConferenceIQ(confIq, options));
    }

    @Test
    public void authorizedSourceTest()
        throws Exception
    {
        String authorizedRegExpr = "^focus@auth.domain.com/.*$";
        bridge.setAuthorizedSourceRegExp(authorizedRegExpr);

        // Make sure we run with no extra options to avoid test failures if
        // we have defaults specified in the system
        int processingOptions = 0;

        expectResult(
            ColibriUtilities.createConferenceIq(
                JidCreate.from("focus@auth.domain.com/focus8969386508643465")),
            processingOptions);

        expectResult(
            ColibriUtilities.createConferenceIq(
                    JidCreate.from("focus@auth.domain.com/fdsfwetg")),
            processingOptions);

        expectNotAuthorized(
            ColibriUtilities.createConferenceIq(
                    JidCreate.from("focus@auth2.domain.com/res1")),
            processingOptions);

        expectNotAuthorized(
            ColibriUtilities.createConferenceIq(
                    JidCreate.from("fdfsgv1@auth.domain23.com/pc3")),
            processingOptions);

        // Check for HealthCheckIQ
        HealthCheckIQ healthCheckIQ = new HealthCheckIQ();

        healthCheckIQ.setFrom(JidCreate.from("focus@auth.domain.com/fdsfwetg"));

        // The bridge returns an error until the first health check completes,
        // and it runs in a separate thread. So give it a few seconds
        Util.waitForEquals(
            "Health check should be successful",
            IQ.Type.result,
            () -> bridge.handleHealthCheckIQ(healthCheckIQ).getType());

        healthCheckIQ.setFrom(JidCreate.from("focus@auth.domain4.com/fdsfwetg"));
        expectNotAuthorized(healthCheckIQ);
    }
}
