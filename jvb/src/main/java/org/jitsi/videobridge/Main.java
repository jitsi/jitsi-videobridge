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
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.glassfish.jersey.servlet.*;
import org.ice4j.ice.harvest.*;
import org.jetbrains.annotations.*;
import org.jitsi.cmd.*;
import org.jitsi.config.*;
import org.jitsi.metaconfig.*;
import org.jitsi.rest.*;
import org.jitsi.service.configuration.*;
import org.jitsi.stats.media.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.ice.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.rest.root.*;
import org.jitsi.videobridge.shutdown.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.websocket.*;
import org.jitsi.videobridge.xmpp.*;

import java.util.*;

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

        setSystemPropertyDefaults();

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

        // Reload the Typesafe config used by ice4j, because the original was initialized before the new system
        // properties were set.
        JitsiConfig.Companion.reloadNewConfig();

        startIce4j();

        OctoRelayService octoRelayService = OctoRelayServiceProviderKt.singleton().get();
        if (octoRelayService != null)
        {
            octoRelayService.start();
        }
        ClientConnectionImpl clientConnectionImpl = ClientConnectionSupplierKt.singleton().get();
        clientConnectionImpl.start();

        final StatsManager statsMgr = StatsManagerSupplierKt.singleton().get();
        if (statsMgr != null)
        {
            statsMgr.addStatistics(new VideobridgeStatistics(), StatsManager.config.getInterval().toMillis());

            StatsManager.config.getTransportConfigs().forEach(transportConfig -> {
                statsMgr.addTransport(transportConfig.toStatsTransport(), transportConfig.getInterval().toMillis());
            });

            statsMgr.start();
        }
        JvbHealthCheckServiceSupplierKt.singleton().get().start();

        Logger logger = new LoggerImpl("org.jitsi.videobridge.Main");

        Server publicHttpServer = setupPublicHttpServer();
        if (publicHttpServer != null)
        {
            logger.info("Starting public http server");
            publicHttpServer.start();
        }
        else
        {
            logger.info("Not starting public http server");
        }

        Server privateHttpServer = setupPrivateHttpServer();
        if (privateHttpServer != null)
        {
            logger.info("Starting private http server");
            privateHttpServer.start();
        }
        else
        {
            logger.info("Not starting private http server");
        }

        ShutdownServiceSupplierKt.singleton().get().waitForShutdown();

        logger.info("Bridge shutting down");

        if (octoRelayService != null)
        {
            octoRelayService.stop();
        }
        clientConnectionImpl.stop();

        if (statsMgr != null)
        {
            statsMgr.stop();
        }

        try
        {
            if (publicHttpServer != null)
            {
                publicHttpServer.stop();
            }
            if (privateHttpServer != null)
            {
                privateHttpServer.stop();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        JvbHealthCheckServiceSupplierKt.singleton().get().stop();

        VideobridgeSupplierKt.getVideobridgeSupplier().get().stop();

        stopIce4j();

        TaskPools.SCHEDULED_POOL.shutdownNow();
        TaskPools.CPU_POOL.shutdownNow();
        TaskPools.IO_POOL.shutdownNow();
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

    private static Server setupPublicHttpServer()
    {
        JettyBundleActivatorConfig publicServerConfig = new JettyBundleActivatorConfig(
            "org.jitsi.videobridge.rest",
            "videobridge.http-servers.public"
        );
        if (publicServerConfig.getPort() == -1 && publicServerConfig.getTlsPort() == -1)
        {
            return null;
        }

        final Server publicServer = JettyHelpers.createServer(publicServerConfig);
        ColibriWebSocketService colibriWebSocketService =
            new ColibriWebSocketService(publicServerConfig.isTls());
        // Now that we've created the ColibriWebSocketService, set it in the central supplier so others can
        // access it.
        ColibriWebSocketServiceSupplierKt.singleton().setColibriWebSocketService(colibriWebSocketService);

        colibriWebSocketService.registerServlet(JettyHelpers.getServletContextHandler(publicServer));

        return publicServer;
    }

    private static Server setupPrivateHttpServer()
    {
        JettyBundleActivatorConfig privateServerConfig = new JettyBundleActivatorConfig(
            "org.jitsi.videobridge.rest.private",
            "videobridge.http-servers.private"
        );
        if (privateServerConfig.getPort() == -1 && privateServerConfig.getTlsPort() == -1)
        {
            return null;
        }

        final Server privateServer = JettyHelpers.createServer(privateServerConfig);

        JettyHelpers.getServletContextHandler(privateServer).addServlet(
            new ServletHolder(
                new ServletContainer(
                    new Application()
                )
            ),
            "/*"
        );

        return privateServer;
    }

    private static void setSystemPropertyDefaults()
    {
        Map<String, String> defaults = getSystemPropertyDefaults();

        for (Map.Entry<String,String> e : defaults.entrySet())
        {
            String key = e.getKey();

            if (System.getProperty(key) == null)
                System.setProperty(key, e.getValue());
        }
    }

    private static Map<String, String> getSystemPropertyDefaults()
    {
        Map<String, String> defaults = new HashMap<>();
        Utils.getCallStatsJavaSDKSystemPropertyDefaults(defaults);

        // Make legacy ice4j properties system properties.
        ConfigurationService cfg = JitsiConfig.getSipCommunicatorProps();
        List<String> ice4jPropertyNames = cfg.getPropertyNamesByPrefix("org.ice4j", false);

        if (ice4jPropertyNames != null && !ice4jPropertyNames.isEmpty())
        {
            for (String propertyName : ice4jPropertyNames)
            {
                String propertyValue = cfg.getString(propertyName);
                if (propertyValue != null)
                {
                    defaults.put(propertyName, propertyValue);
                }
            }
        }

        return defaults;
    }

    private static void startIce4j()
    {

        // Start the initialization of the mapping candidate harvesters.
        // Asynchronous, because the AWS and STUN harvester may take a long
        // time to initialize.
        new Thread(MappingCandidateHarvesters::initialize).start();
    }

    private static void stopIce4j()
    {
        // Shut down harvesters.
        Harvesters.closeStaticConfiguration();

        for (Map.Entry<Object, Object> property : System.getProperties().entrySet())
        {
            if (property.getKey().toString().startsWith("org.ice4j"))
            {
                System.clearProperty(property.getKey().toString());
            }
        }
    }
}
