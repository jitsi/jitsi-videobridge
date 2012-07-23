package org.jitsi.videobridge;

import net.java.sip.communicator.service.neomedia.*;

import org.jivesoftware.whack.*;
import org.xmpp.component.*;

/**
 * Provides the <tt>main</tt> entry point of the Jitsi VideoBridge application
 * which implements an external Jabber component.
 *
 * @author Lyubomir Marinov
 */
public class Main
{
    /**
     * The <tt>Object</tt> which synchronizes the access to the state related to
     * the decision whether the application is to exit. At the time of this
     * writing, the application just runs until it is killed.
     */
    private static final Object exitSyncRoot = new Object();

    private static final String HOST_ARG_NAME = "--host=";

    /**
     * The default value of the {@link #HOST_ARG_NAME} command-line argument if
     * it is not explicitly provided.
     */
    private static final String HOST_ARG_VALUE = "localhost";

    /**
     * The name of the command-line argument which specifies the value of the
     * <tt>System</tt> property
     * {@link DefaultStreamConnector#MAX_PORT_NUMBER_PROPERTY_NAME}.
     */
    private static final String MAX_PORT_ARG_NAME = "--max-port=";

    /**
     * The name of the command-line argument which specifies the value of the
     * <tt>System</tt> property
     * {@link DefaultStreamConnector#MIN_PORT_NUMBER_PROPERTY_NAME}.
     */
    private static final String MIN_PORT_ARG_NAME = "--min-port=";

    private static final String PORT_ARG_NAME = "--port=";

    /**
     * The default value of the {@link #PORT_ARG_NAME} command-line argument if
     * it is not explicitly provided.
     */
    private static final int PORT_ARG_VALUE = 5275;

    private static final String SECRET_ARG_NAME = "--secret=";

    /**
     * Represents the <tt>main</tt> entry point of the Jitsi VideoBridge
     * application which implements an external Jabber component.
     *
     * @param args the arguments provided to the application on the command line
     * @throws Exception if anything goes wrong and the condition cannot be
     * gracefully handled during the execution of the application
     */
    public static void main(String[] args)
        throws Exception
    {
        // Parse the command-line arguments.
        String host = HOST_ARG_VALUE;
        String maxPort = null;
        String minPort = null;
        int port = PORT_ARG_VALUE;
        String secret = null;

        for (String arg : args)
        {
            if (arg.startsWith(HOST_ARG_NAME))
                host = arg.substring(HOST_ARG_NAME.length());
            else if (arg.startsWith(MAX_PORT_ARG_NAME))
                maxPort = arg.substring(MAX_PORT_ARG_NAME.length());
            else if (arg.startsWith(MIN_PORT_ARG_NAME))
                minPort = arg.substring(MIN_PORT_ARG_NAME.length());
            else if (arg.startsWith(PORT_ARG_NAME))
                port = Integer.parseInt(arg.substring(PORT_ARG_NAME.length()));
            else if (arg.startsWith(SECRET_ARG_NAME))
                secret = arg.substring(SECRET_ARG_NAME.length());
        }

        // Start Jitsi VideoBridge as an external Jabber component. 
        ExternalComponentManager componentManager
            = new ExternalComponentManager(host, port);
        String subdomain = ComponentImpl.SUBDOMAIN;

        componentManager.setMultipleAllowed(subdomain, true);
        componentManager.setSecretKey(subdomain, secret);

        /*
         * Before initializing the Component implementation, set any System
         * properties it uses.
         */
        if ((maxPort != null) && (maxPort.length() != 0))
        {
            System.setProperty(
                    DefaultStreamConnector.MAX_PORT_NUMBER_PROPERTY_NAME,
                    maxPort);
        }
        if ((minPort != null) && (minPort.length() != 0))
        {
            System.setProperty(
                    DefaultStreamConnector.MIN_PORT_NUMBER_PROPERTY_NAME,
                    minPort);
        }

        Component component = new ComponentImpl();

        componentManager.addComponent(subdomain, component);

        while (true)
        {
            boolean interrupted = false;

            synchronized (exitSyncRoot)
            {
                try
                {
                    exitSyncRoot.wait();
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }
}
