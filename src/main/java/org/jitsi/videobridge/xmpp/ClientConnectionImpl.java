/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.videobridge.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import net.java.sip.communicator.util.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.xmpp.mucclient.*;
import org.jivesoftware.smack.packet.*;
import org.osgi.framework.*;

import java.util.*;

/**
 * Provides jitsi-videobridge functions through an XMPP client connection.
 *
 * @author Boris Grozev
 */
public class ClientConnectionImpl
    implements BundleActivator, IQListener
{
    /**
     * The {@link Logger} used by the {@link ClientConnectionImpl}
     * class and its instances for logging output.
     */
    private static final org.jitsi.util.Logger logger
        =  org.jitsi.util.Logger.getLogger(ClientConnectionImpl.class);

    /**
     * The prefix of the property names used to configure this bundle.
     */
    private static final String PREFIX = "org.jitsi.videobridge.xmpp.user.";

    /**
     * The {@link MucClientManager} which manages the XMPP user connections
     * and the MUCs.
     */
    private MucClientManager mucClientManager;

    /**
     * The {@link XmppCommon} instance which implements handling of Smack IQs
     * for this {@link ClientConnectionImpl}.
     */
    private final XmppCommon common = new XmppCommon();

    /**
     * Starts this bundle.
     */
    @Override
    public void start(BundleContext bundleContext)
    {
        ConfigurationService config
            = ServiceUtils.getService(
                bundleContext, ConfigurationService.class);
        if (config == null)
        {
            logger.info("Not using XMPP user login; no config service.");
            return;
        }

        // Register this instance as an OSGi service.
        Collection<ClientConnectionImpl> userLoginBundles
            = ServiceUtils2.getServices(
                bundleContext, ClientConnectionImpl.class);

        if (!userLoginBundles.contains(this))
        {
            common.start(bundleContext);

            mucClientManager = new MucClientManager(XmppCommon.FEATURES);

            // These are the IQs that we are interested in.
            mucClientManager.registerIQ(new HealthCheckIQ());
            mucClientManager.registerIQ(new ColibriConferenceIQ());
            mucClientManager.registerIQ(
                new org.jivesoftware.smackx.iqversion.packet.Version());
            mucClientManager.registerIQ(ShutdownIQ.createForceShutdownIQ());
            mucClientManager.registerIQ(
                ShutdownIQ.createGracefulShutdownIQ());
            mucClientManager.setIQListener(this);

            Collection<MucClientConfiguration> configurations
                = MucClientConfiguration.loadFromConfigService(
                    config, PREFIX, true);
            configurations.forEach(c -> mucClientManager.addMucClient(c));

            bundleContext.registerService(
                ClientConnectionImpl.class, this, null);
        }
        else
        {
            logger.error("Already started");
        }
    }

    /**
     * Processes an {@link IQ} received by one of the {@link MucClient}s of
     * {@link #mucClientManager}.
     *
     * @param iq the IQ to process.
     * @return the IQ to send as a response, or {@code null}.
     */
    @Override
    public IQ handleIq(IQ iq)
    {
        return common.handleIQ(iq);
    }

    /**
     * Stops this bundle.
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        try
        {
            // Unregister this instance as an OSGi service.
            Collection<ServiceReference<ComponentImpl>> serviceReferences
                = bundleContext.getServiceReferences(ComponentImpl.class, null);

            if (serviceReferences != null)
            {
                for (ServiceReference<ComponentImpl> serviceReference
                    : serviceReferences)
                {
                    Object service = bundleContext.getService(serviceReference);

                    if (service == this)
                        bundleContext.ungetService(serviceReference);
                }
            }
        }
        finally
        {
            common.stop(bundleContext);
        }
    }

    /**
     * Adds an {@link ExtensionElement} to our presence, and removes any other
     * extensions with the same element name and namespace, if any exists.
     * @param extension the extension to add.
     */
    public void setPresenceExtension(ExtensionElement extension)
    {
        mucClientManager.setPresenceExtension(extension);
    }
}
