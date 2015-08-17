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
package org.jitsi.videobridge.influxdb;

import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.eventadmin.*;
import org.jitsi.videobridge.osgi.*;
import org.osgi.framework.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Implements a <tt>BundleActivator</tt> for <tt>LoggingService</tt>.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class Activator
        implements BundleActivator
{
    /**
     * The <tt>Logger</tt> used by the <tt>Activator</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(LoggingHandler.class);

    /**
     * The name of the property which configured the class to use as
     * <tt>LoggingHandler</tt> implementation.
     */
    public static final String LOGGING_HANDLER_CLASS_PNAME
        = "org.jitsi.videobridge.influxdb.LOGGING_HANDLER";

    private ServiceRegistration<EventHandler> serviceRegistration;

    /**
     * Initializes a <tt>LoggingService</tt>.
     *
     * @param bundleContext the <tt>bundleContext</tt> to use.
     * @throws Exception
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        ConfigurationService cfg =
            ServiceUtils2.getService(bundleContext, ConfigurationService.class);

        if (cfg.getBoolean(LoggingHandler.ENABLED_PNAME, false))
        {
            LoggingHandler handler;

            try
            {
                handler = getHandlerInstance(cfg);
            }
            catch (Exception e)
            {
                logger.warn("Failed to instantiate LoggingHandler: " + e);
                return;
            }

            Dictionary props = new Hashtable();
            String[] topics = { "org/jitsi/*" };

            props.put(EventConstants.EVENT_TOPIC, topics);

            serviceRegistration
                = bundleContext.registerService(
                        EventHandler.class,
                        handler,
                        props);
        }
    }

    /**
     * Creates and returns an implementation of <tt>LoggingHandler</tt>. The
     * exact class to instantiate is taken from <tt>cfg</tt>.
     */
    private LoggingHandler getHandlerInstance(ConfigurationService cfg)
        throws Exception
    {
        String className
            = cfg.getString(
                    LOGGING_HANDLER_CLASS_PNAME,
                    LoggingHandler.class.getCanonicalName());
        Class<?> clazz = Class.forName(className);
        Constructor<?> constructor
            = clazz.getConstructor(ConfigurationService.class);

        return (LoggingHandler) constructor.newInstance(cfg);
    }

    /**
     * Removes the previously initialized <tt>LoggingService</tt> instance from
     * <tt>bundleContext</tt>.
     *
     * @param bundleContext the <tt>bundleContext</tt> to use.
     * @throws Exception
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (serviceRegistration != null)
            serviceRegistration.unregister();
    }
}
