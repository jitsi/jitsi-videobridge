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

import java.util.*;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.shutdown.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.osgi.*;
import org.jitsi.videobridge.xmpp.*;
import org.jivesoftware.whack.*;
import org.osgi.framework.*;
import org.xmpp.component.*;

/**
 * Provides the <tt>main</tt> entry point of the Jitsi Videobridge application
 * which implements an external Jabber component.
 * <p>
 * Jitsi Videobridge implements two application programming interfaces (APIs):
 * XMPP and REST (HTTP/JSON). The APIs to be activated by the application are
 * specified with the command-line argument <tt>--apis=</tt> the value of which
 * is a comma-separated list of <tt>xmpp</tt> and <tt>rest</tt>. The default
 * value is <tt>xmpp</tt> (i.e. if the command-line argument <tt>--apis=</tt> is
 * not explicitly specified, the application behaves as if <tt>--args=xmpp</tt>
 * is specified). For example, specify <tt>--apis=rest,xmpp</tt> on the comamnd
 * line to simultaneously enable the two APIs.
 * </p>
 *
 * @author Lyubomir Marinov
 */
public class Main
{
    /**
     * The name of the command-line argument which specifies the application
     * programming interfaces (APIs) to enable for Jitsi Videobridge.
     */
    private static final String APIS_ARG_NAME = "--apis=";

    /**
     * The name of the command-line argument which specifies the XMPP domain
     * to use.
     */
    private static final String DOMAIN_ARG_NAME = "--domain=";

    /**
     * The <tt>Object</tt> which synchronizes the access to the state related to
     * the decision whether the application is to exit. At the time of this
     * writing, the application just runs until it is killed.
     */
    private static final Object exitSyncRoot = new Object();

    /**
     * The name of the command-line argument which specifies the IP address or
     * the name of the XMPP host to connect to.
     */
    private static final String HOST_ARG_NAME = "--host=";

    /**
     * The default value of the {@link #HOST_ARG_NAME} command-line argument if
     * it is not explicitly provided.
     */
    private static final String HOST_ARG_VALUE = "localhost";

    /**
     * The logger instance used.
     */
    private static Logger logger = Logger.getLogger(Main.class);

    /**
     * The name of the command-line argument which specifies the value of the
     * <tt>System</tt> property
     * {@link DefaultStreamConnector#MAX_PORT_NUMBER_PROPERTY_NAME}.
     */
    private static final String MAX_PORT_ARG_NAME = "--max-port=";

    /**
     * The default value of the {@link #MAX_PORT_ARG_NAME} command-line argument
     * if it is not explicitly provided.
     */
    private static final String MAX_PORT_ARG_VALUE = "20000";

    /**
     * The name of the command-line argument which specifies the value of the
     * <tt>System</tt> property
     * {@link DefaultStreamConnector#MIN_PORT_NUMBER_PROPERTY_NAME}.
     */
    private static final String MIN_PORT_ARG_NAME = "--min-port=";

    /**
     * The default value of the {@link #MIN_PORT_ARG_NAME} command-line argument
     * if
     * it is not explicitly provided.
     */
    private static final String MIN_PORT_ARG_VALUE = "10000";

    /**
     * The name of the command-line argument which specifies the port of the
     * XMPP host to connect on.
     */
    private static final String PORT_ARG_NAME = "--port=";

    /**
     * The default value of the {@link #PORT_ARG_NAME} command-line argument if
     * it is not explicitly provided.
     */
    private static final int PORT_ARG_VALUE = 5275;

    /**
     * The name of the command-line argument which specifies the secret key for
     * the sub-domain of the Jabber component implemented by this application
     * with which it is to authenticate to the XMPP server to connect to.
     */
    private static final String SECRET_ARG_NAME = "--secret=";

    /**
     * The name of the command-line argument which specifies sub-domain name for
     * the videobridge component.
     */
    private static final String SUBDOMAIN_ARG_NAME = "--subdomain=";

    /**
     * Represents the <tt>main</tt> entry point of the Jitsi Videobridge
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
        List<String> apis = new LinkedList<String>();
        String host = null;
        String maxPort = MAX_PORT_ARG_VALUE;
        String minPort = MIN_PORT_ARG_VALUE;
        int port = PORT_ARG_VALUE;
        String secret = "";
        String domain = null;
        String subdomain = ComponentImpl.SUBDOMAIN;

        for (String arg : args)
        {
            if (arg.startsWith(APIS_ARG_NAME))
            {
                for (String api
                        : arg.substring(APIS_ARG_NAME.length()).split(","))
                {
                    if ((api != null)
                            && (api.length() != 0)
                            && !apis.contains(api))
                    {
                        apis.add(api);
                    }
                }
            }
            else if (arg.startsWith(DOMAIN_ARG_NAME))
            {
                domain = arg.substring(DOMAIN_ARG_NAME.length());
            }
            else if (arg.startsWith(HOST_ARG_NAME))
            {
                host = arg.substring(HOST_ARG_NAME.length());
            }
            else if (arg.startsWith(MAX_PORT_ARG_NAME))
            {
                maxPort = arg.substring(MAX_PORT_ARG_NAME.length());
            }
            else if (arg.startsWith(MIN_PORT_ARG_NAME))
            {
                minPort = arg.substring(MIN_PORT_ARG_NAME.length());
            }
            else if (arg.startsWith(PORT_ARG_NAME))
            {
                port = Integer.parseInt(arg.substring(PORT_ARG_NAME.length()));
            }
            else if (arg.startsWith(SECRET_ARG_NAME))
            {
                secret = arg.substring(SECRET_ARG_NAME.length());
            }
            else if (arg.startsWith(SUBDOMAIN_ARG_NAME))
            {
                subdomain = arg.substring(SUBDOMAIN_ARG_NAME.length());
            }
        }

        if (apis.isEmpty())
            apis.add(Videobridge.XMPP_API);
        if (host == null)
            host = (domain == null) ? HOST_ARG_VALUE : domain;

        /*
         * Before initializing the application programming interfaces (APIs) of
         * Jitsi Videobridge, set any System properties which they use and which
         * may be specified by the command-line arguments.
         */
        System.setProperty(
                Videobridge.REST_API_PNAME,
                Boolean.toString(apis.contains(Videobridge.REST_API)));
        System.setProperty(
                Videobridge.XMPP_API_PNAME,
                Boolean.toString(apis.contains(Videobridge.XMPP_API)));
        if ((maxPort != null) && (maxPort.length() != 0))
        {
            // Jingle Raw UDP transport
            System.setProperty(
                    DefaultStreamConnector.MAX_PORT_NUMBER_PROPERTY_NAME,
                    maxPort);
            // Jingle ICE-UDP transport
            System.setProperty(
                    OperationSetBasicTelephony
                        .MAX_MEDIA_PORT_NUMBER_PROPERTY_NAME,
                    maxPort);
        }
        if ((minPort != null) && (minPort.length() != 0))
        {
            // Jingle Raw UDP transport
            System.setProperty(
                    DefaultStreamConnector.MIN_PORT_NUMBER_PROPERTY_NAME,
                    minPort);
            // Jingle ICE-UDP transport
            System.setProperty(
                    OperationSetBasicTelephony
                        .MIN_MEDIA_PORT_NUMBER_PROPERTY_NAME,
                    minPort);
        }

        /*
         * Start OSGi. It will invoke the application programming interfaces
         * (APIs) of Jitsi Videobridge. Each of them will keep the application
         * alive. 
         */
        OSGi.start(
                new BundleActivator()
                {
                    @Override
                    public void start(BundleContext bundleContext)
                        throws Exception
                    {
                        // TODO Auto-generated method stub
                        registerShutdownService(
                            bundleContext, Thread.currentThread(), this);
                    }

                    @Override
                    public void stop(BundleContext bundleContext)
                        throws Exception
                    {
                        // TODO Auto-generated method stub
                    }
                });

        // Start Jitsi Videobridge as an external Jabber component.
        if (apis.contains(Videobridge.XMPP_API))
        {
            ExternalComponentManager componentManager
                = new ExternalComponentManager(host, port);

            componentManager.setMultipleAllowed(subdomain, true);
            componentManager.setSecretKey(subdomain, secret);
            if (domain != null)
                componentManager.setServerName(domain);
    
            Component component = new ComponentImpl();

            componentManager.addComponent(subdomain, component);

            /*
             * The application has nothing more to do but wait for ComponentImpl
             * to perform its duties. Presently, there is no specific shutdown
             * procedure and the application just gets killed.
             */
            do
            {
                synchronized (exitSyncRoot)
                {
                    try
                    {
                        exitSyncRoot.wait();
                    }
                    catch (InterruptedException ie)
                    {
                        break;
                    }
                }
            }
            while (true);

            try
            {
                componentManager.removeComponent(subdomain);
            }
            catch (ComponentException e)
            {
                logger.error(e, e);
            }
        }
    }

    /**
     * Registers {@link ShutdownService} implementation for videobridge
     * application.
     * @param bundleContext the OSGi context
     * @param mainThread main application thread
     * @param mainBundleActivator main bundle activator that will be used for
     *                            stopping the OSGi.
     */
    private static void registerShutdownService(
            BundleContext bundleContext,
            final Thread mainThread,
            final BundleActivator mainBundleActivator)
    {
        bundleContext.registerService(
            ShutdownService.class,
            new ShutdownService()
            {
                private boolean shutdownStarted = false;

                @Override
                public void beginShutdown()
                {
                    if (shutdownStarted)
                        return;

                    shutdownStarted = true;

                    new Thread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            mainThread.interrupt();

                            OSGi.stop(mainBundleActivator);
                        }
                    }, "JVB-Shutdown-Thread").start();
                }
            }, null
        );
    }
}
