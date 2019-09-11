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

import org.jitsi.cmd.*;
import org.jitsi.meet.*;
import org.jitsi.videobridge.osgi.*;
import org.jitsi.videobridge.xmpp.*;

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
 * is specified). For example, specify <tt>--apis=rest,xmpp</tt> on the command
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
    private static final String APIS_ARG_NAME = "--apis";

    /**
     * The name of the command-line argument which specifies the XMPP domain
     * to use.
     */
    private static final String DOMAIN_ARG_NAME = "--domain";

    /**
     * The name of the command-line argument which specifies the IP address or
     * the name of the XMPP host to connect to.
     */
    private static final String HOST_ARG_NAME = "--host";

    /**
     * The default value of the {@link #HOST_ARG_NAME} command-line argument if
     * it is not explicitly provided.
     */
    private static final String HOST_ARG_VALUE = "localhost";

    /**
     * The name of the command-line argument which specifies the port of the
     * XMPP host to connect on.
     */
    private static final String PORT_ARG_NAME = "--port";

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
    private static final String SECRET_ARG_NAME = "--secret";

    /**
     * The name of the command-line argument which specifies sub-domain name for
     * the videobridge component.
     */
    private static final String SUBDOMAIN_ARG_NAME = "--subdomain";

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
        CmdLine cmdLine = new CmdLine();

        cmdLine.parse(args);

        // Parse the command-line arguments.
        String apis
            = cmdLine.getOptionValue(APIS_ARG_NAME, Videobridge.XMPP_API);
        String domain = cmdLine.getOptionValue(DOMAIN_ARG_NAME, null);
        int port = cmdLine.getIntOptionValue(PORT_ARG_NAME, PORT_ARG_VALUE);
        String secret = cmdLine.getOptionValue(SECRET_ARG_NAME, "");
        String subdomain
            = cmdLine.getOptionValue(
                    SUBDOMAIN_ARG_NAME, ComponentImpl.SUBDOMAIN);

        String host
            = cmdLine.getOptionValue(
                    HOST_ARG_NAME,
                    domain == null ? HOST_ARG_VALUE : domain);

        // Before initializing the application programming interfaces (APIs) of
        // Jitsi Videobridge, set any System properties which they use and which
        // may be specified by the command-line arguments.
        System.setProperty(
                Videobridge.REST_API_PNAME,
                Boolean.toString(apis.contains(Videobridge.REST_API)));
        System.setProperty(
                Videobridge.XMPP_API_PNAME,
                Boolean.toString(apis.contains(Videobridge.XMPP_API)));

        ComponentMain main = new ComponentMain();
        BundleConfig osgiBundles = new BundleConfig();

        // Start Jitsi Videobridge as an external Jabber component.
        if (apis.contains(Videobridge.XMPP_API))
        {
            ComponentImpl component
                = new ComponentImpl(
                        host,
                        port,
                        domain,
                        subdomain,
                        secret);

            main.runMainProgramLoop(component, osgiBundles);
        }
        else
        {
            main.runMainProgramLoop(osgiBundles);
        }
    }
}
