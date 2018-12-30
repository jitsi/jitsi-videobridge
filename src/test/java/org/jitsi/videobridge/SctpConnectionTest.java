package org.jitsi.videobridge;

import org.junit.*;

import static org.junit.Assert.*;

public class SctpConnectionTest
{
    /**
     * Tested <tt>SctpConnection</tt> instance.
     */
    private static SctpConnection sctpConnection;

    private static OSGiHandler osgiHandler = new OSGiHandler();

    /**
     * Initializes OSGi
     */
    @BeforeClass
    public static void setUp()
        throws InterruptedException
    {
        osgiHandler.start();
        sctpConnection = osgiHandler.getService(SctpConnection.class);
    }

    /**
     * Shutdown OSGi
     */
    @AfterClass
    public static void tearDown()
        throws InterruptedException
    {
        osgiHandler.stop();
    }

    /**
     * Tests that connection-ready notifications are sent after a new
     * connection is accepted
     */
    @Test
    public void testConnectionReadyNotifications()
    {
        assertTrue(false);
    }
}
