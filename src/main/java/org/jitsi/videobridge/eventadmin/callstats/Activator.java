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
package org.jitsi.videobridge.eventadmin.callstats;

import org.jitsi.eventadmin.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.stats.media.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.stats.config.*;
import org.osgi.framework.*;

/**
 * OSGi {@code BundleActivator} implementation for the CallStats client
 * statistics.
 *
 * @author Damian Minkov
 */
public class Activator
    implements BundleActivator,
               ServiceListener
{
    /**
     * The OSGi <tt>BundleContext</tt> in which this <tt>Activator</tt> has been
     * started.
     */
    private BundleContext bundleContext;

    /**
     * Handles conference stats.
     */
    private CallStatsConferenceStatsHandler conferenceStatsHandler;

    private ServiceRegistration<EventHandler> serviceRegistration;

    /**
     * Starts this {@code Activator}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code Activator} is starting
     * @throws Exception error starting this activator
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        this.bundleContext = bundleContext;

        bundleContext.addServiceListener(this);
    }

    /**
     * Stops this {@code Activator}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code Activator} is stopping
     * @throws Exception error stopping and removing dependent services.
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        bundleContext.removeServiceListener(this);

        if (serviceRegistration != null)
        {
            serviceRegistration.unregister();
            serviceRegistration = null;
        }

        if (conferenceStatsHandler != null)
        {
            conferenceStatsHandler.stop();
            conferenceStatsHandler = null;
        }
    }

    /**
     * Listens to service registrations of the callstats library and
     * starts/stops this implementation depending on the type of event i.e.
     * REGISTERED/UNREGISTERING.
     *
     * @param ev the {@code ServiceEvent}
     */
    @Override
    public void serviceChanged(ServiceEvent ev)
    {
        if (bundleContext == null)
            return;

        Object service;

        try
        {
            service = bundleContext.getService(ev.getServiceReference());
        }
        catch (IllegalArgumentException
            | IllegalStateException
            | SecurityException ex)
        {
            service = null;
        }

        if (service == null || !(service instanceof StatsService))
            return;

        switch (ev.getType())
        {
        case ServiceEvent.REGISTERED:
            ConfigurationService cfg = ServiceUtils2.getService(
                bundleContext, ConfigurationService.class);
            String bridgeId = CallStatsIoTransportConfig.bridgeId.get();
            int interval = StatsIntervalProperty.getInstance().get();

            // Update with per stats transport interval if available.
            StatsTransport callStatsTransport =
                StatsTransportsProperty.getInstance().getStatsTransportByType(CallStatsIOTransport.class);
            if (callStatsTransport != null)
            {
                interval = (int)callStatsTransport.getInterval().toMillis();
            }

            String conferenceIDPrefix = CallStatsIoTransportConfig.conferenceIdPrefix.get();

            conferenceStatsHandler = new CallStatsConferenceStatsHandler();
            conferenceStatsHandler.start(
                (StatsService) service,
                bridgeId,
                conferenceIDPrefix,
                interval);

            String[] topics = { "org/jitsi/*" };

            serviceRegistration = EventUtil.registerEventHandler(
                bundleContext,
                topics,
                conferenceStatsHandler);
            break;

        case ServiceEvent.UNREGISTERING:
            if (conferenceStatsHandler != null)
            {
                conferenceStatsHandler.stop();
                conferenceStatsHandler = null;
            }
            break;
        }
    }
}
