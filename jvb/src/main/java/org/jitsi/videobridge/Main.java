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

import kotlin.jvm.functions.*;
import org.jetbrains.annotations.*;
import org.jitsi.cmd.*;
import org.jitsi.meet.*;
import org.jitsi.metaconfig.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.osgi.*;
import org.jitsi.videobridge.stats.*;
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

        setupMetaconfigLogger();

        // Parse the command-line arguments.
        String apis = cmdLine.getOptionValue(APIS_ARG_NAME);

        // Some of our dependencies bring in slf4j, which means Jetty will default to using
        // slf4j as its logging backend.  The version of slf4j brought in, however, is too old
        // for Jetty so it throws errors.  We use java.util.logging so tell Jetty to use that
        // as its logging backend.
        //TODO: Instead of setting this here, we should integrate it with the infra/debian scripts
        // to be passed.
        System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.JavaUtilLog");

        // Before initializing the application programming interfaces (APIs) of
        // Jitsi Videobridge, set any System properties which they use and which
        // may be specified by the command-line arguments.
        System.setProperty(
                Videobridge.REST_API_PNAME,
                Boolean.toString(apis.contains(Videobridge.REST_API)));

        OctoRelayService octoRelayService = OctoRelayServiceProviderKt.singleton().get();
        if (octoRelayService != null)
        {
            octoRelayService.start();
        }
        ClientConnectionImpl clientConnectionImpl = ClientConnectionSupplierKt.singleton.get();
        clientConnectionImpl.start();

        final StatsManager statsMgr = StatsManagerSupplierKt.singleton.get();
        if (statsMgr != null)
        {
            statsMgr.addStatistics(new VideobridgeStatistics(), StatsManager.config.getInterval().toMillis());

            StatsManager.config.getTransportConfigs().forEach(transportConfig -> {
                statsMgr.addTransport(transportConfig.toStatsTransport(), transportConfig.getInterval().toMillis());
            });

            statsMgr.start();
        }

        Logger logger = new LoggerImpl("org.jitsi.videobridge.Main");

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            logger.info("Shutdown hook running");
            if (octoRelayService != null)
            {
                octoRelayService.stop();
            }
            clientConnectionImpl.stop();

            if (statsMgr != null)
            {
                statsMgr.stop();
            }
        }));

        ComponentMain main = new ComponentMain();
        BundleConfig osgiBundles = new BundleConfig();

        main.runMainProgramLoop(osgiBundles);
    }

    private static void setupMetaconfigLogger() {
        Logger configLogger = new LoggerImpl("org.jitsi.config");
        MetaconfigSettings.Companion.setLogger(new MetaconfigLogger()
        {
            @Override
            public void warn(@NotNull Function0<String> function0)
            {
                configLogger.warn(function0::invoke);
            }

            @Override
            public void error(@NotNull Function0<String> function0)
            {
                configLogger.error(function0::invoke);
            }

            @Override
            public void debug(@NotNull Function0<String> function0)
            {
                configLogger.debug(function0::invoke);
            }
        });
    }
}
