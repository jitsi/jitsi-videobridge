/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import org.junit.runner.*;
import org.junit.runners.*;

/**
 * Test suite for the videobridge.
 *
 * @author Pawel Domas
 */
@RunWith(Suite.class)
@Suite.SuiteClasses(
    {
        FocusControlTest.class,
        BridgeShutdownTest.class // This one must be the last one
    })
public class VideoBridgeTestSuite
{
}
