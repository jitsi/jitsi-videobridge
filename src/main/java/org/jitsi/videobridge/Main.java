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
import org.jitsi.utils.config.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.osgi.*;
import org.jitsi.videobridge.xmpp.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

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

    private static final Logger logger = new LoggerImpl(Main.class.getName());

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

        // Some of our dependencies bring in slf4j, which means Jetty will default to using
        // slf4j as its logging backend.  The version of slf4j brought in, however, is too old
        // for Jetty so it throws errors.  We use java.util.logging so tell Jetty to use that
        // as its logging backend.
        //TODO: Instead of setting this here, we should integrate it with the infra/debian scripts
        // to be passed.
        System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.JavaUtilLog");


        validateConfig();

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

    //TODO(brian): clean this method up
    protected static void validateConfig()
    {
        Reflections r = new Reflections(new ConfigurationBuilder()
            .setUrls(ClasspathHelper.forPackage("org.jitsi"))
            .setScanners(
                new SubTypesScanner(),
                new TypeAnnotationsScanner()
            )
        );

        Set<Class<? extends ConfigProperty>> ss = r.getSubTypesOf(ConfigProperty.class);

        ss = ss.stream().filter(c -> c.isAnnotationPresent(ObsoleteConfig.class)).collect(Collectors.toSet());

        for (Class<? extends ConfigProperty> c : ss)
        {
            try
            {
                Constructor<? extends ConfigProperty> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                ctor.newInstance().get();
                ObsoleteConfig anno = c.getAnnotation(ObsoleteConfig.class);
                logger.info("Prop " + c + " is obsolete but was present in config: " + anno.value());
            }
            catch (InvocationTargetException e)
            {
                // We don't get a raw ConfigPropertyNotFoundException
                // when calling it this way, instead it's wrapped by
                // an InvocationTargetException
                if (e.getCause() instanceof ConfigPropertyNotFoundException)
                {
                    logger.info("Prop " + c + " is obsolete but wasn't found defined, ok!");
                }
            }
            catch (NoSuchMethodException ignored) {
                // This can happen if the class we found was an abstract class,
                // for example ConfigPropertyImpl
            }
            catch (Exception ignored) {}
        }

//        Set<Class<?>> obsoleteConfigProperties =
//            new Reflections("org.jitsi").getTypesAnnotatedWith(ObsoleteConfig.class);
//
//        //TODO: I think there should be a way to narrow down the query above to get subtypes of ConfigProperty
//        // with this annotation, but not sure yet.  This way is fine, but I think the way I just described
//        // would be ideal.
//        for (Class<?> clazz : obsoleteConfigProperties)
//        {
//            if (ConfigProperty.class.isAssignableFrom(clazz))
//            {
////                ConfigProperty<?> cp = ((ConfigProperty)clazz);
//
//            }
//            ObsoleteConfig anno = clazz.getAnnotation(ObsoleteConfig.class);
//            logger.warn("WARNING: You have configuration property " + clazz.getName() + " defined but it " +
//                "is no longer used: " + anno.value());
//        }

    }
}
