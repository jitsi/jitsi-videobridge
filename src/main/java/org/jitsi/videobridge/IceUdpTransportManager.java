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

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.CandidateType;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.socket.*;
import org.jitsi.eventadmin.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.transport.*;
import org.osgi.framework.*;

/**
 * Implements the Jingle ICE-UDP transport.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 * @author Boris Grozev
 */
public abstract class IceUdpTransportManager
    extends TransportManager
{
    /**
     * The name default of the single <tt>IceStream</tt> that this
     * <tt>TransportManager</tt> will create/use.
     */
    private static final String DEFAULT_ICE_STREAM_NAME = "stream";

    /**
     * The {@link Logger} used by the {@link IceUdpTransportManager} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(IceUdpTransportManager.class);

    /**
     * The name of the property that can be used to control the value of
     * {@link #iceUfragPrefix}.
     */
    public static final String ICE_UFRAG_PREFIX_PNAME
            = "org.jitsi.videobridge.ICE_UFRAG_PREFIX";
    /**
     * The optional prefix to use for generated ICE local username fragments.
     */
    private static String iceUfragPrefix;

    /**
     * Whether the "component socket" feature of ice4j should be used. If this
     * feature is used, ice4j will create a separate merging socket instance
     * for each component, which reads from the sockets of all successful
     * candidate pairs. Otherwise, this merging socket instance is not created,
     * and the sockets from the individual candidate pairs should be used
     * directly.
     */
    private static boolean useComponentSocket = true;

    /**
     * The name of the property which configures {@link #useComponentSocket}.
     */
    public static final String USE_COMPONENT_SOCKET_PNAME
        = "org.jitsi.videobridge.USE_COMPONENT_SOCKET";

    /**
     * The name of the property used to control {@link #keepAliveStrategy}.
     */
    public static final String KEEP_ALIVE_STRATEGY_PNAME
            = "org.jitsi.videobridge.KEEP_ALIVE_STRATEGY";

    /**
     * The {@link KeepAliveStrategy} to configure for ice4j {@link Component}s,
     * which will dictate which candidate pairs to keep alive.
     * Default to keeping alive the selected pair and any TCP pairs.
     */
    private static KeepAliveStrategy keepAliveStrategy
            = KeepAliveStrategy.SELECTED_AND_TCP;

    /**
     * Whether this <tt>TransportManager</tt> has been closed.
     */
    private boolean closed = false;

    /**
     * The <tt>Conference</tt> object that this <tt>TransportManager</tt> is
     * associated with.
     */
    protected final Conference conference;

    /**
     * The {@link DiagnosticContext} of this diagnostic instance provider.
     */
    private final DiagnosticContext diagnosticContext = new DiagnosticContext();

    /**
     * An identifier of this {@link IceUdpTransportManager}.
     */
    private final String id;

    /**
     * The ICE {@link Agent}.
     */
    protected Agent iceAgent;

    /**
     * Whether ICE connectivity has been established.
     */
    protected boolean iceConnected = false;

    /**
     * The <tt>IceMediaStream</tt> of {@link #iceAgent} associated with the
     * <tt>Channel</tt> of this instance.
     */
    private final IceMediaStream iceStream;

    /**
     * The <tt>PropertyChangeListener</tt> which is (to be) notified about
     * changes in the properties of the <tt>CandidatePair</tt>s of
     * {@link #iceStream}.
     */
    private final PropertyChangeListener iceStreamPairChangeListener
        = this::iceStreamPairChange;

    /**
     * Whether this <tt>IceUdpTransportManager</tt> will serve as the the
     * controlling or controlled ICE agent.
     */
    private final boolean controlling;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param conference the <tt>Conference</tt> which created this
     * <tt>TransportManager</tt>.
     * @param controlling {@code true} if the new instance is to serve as a
     * controlling ICE agent and passive DTLS endpoint; otherwise, {@code false}
     * @param numComponents the number of ICE components that this instance is
     * to start with.
     * @param iceStreamName the name of the ICE stream to be created by this
     * instance.
     * @param id an identifier of the {@link IceUdpTransportManager}.
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean controlling,
                                  int numComponents,
                                  String iceStreamName,
                                  String id)
        throws IOException
    {
        if (numComponents != 1)
        {
            throw new Error("Non-RTCPMUX currently unsupported");
        }
        this.conference = conference;
        this.id = id;
        this.controlling = controlling;
        this.logger = Logger.getLogger(classLogger, conference.getLogger());

        // Setup the diagnostic context.
        conference.appendDiagnosticInformation(diagnosticContext);
        diagnosticContext.put("transport", hashCode());

        iceAgent = createIceAgent(controlling, iceStreamName, true /* rtcpmux */);
        iceStream = iceAgent.getStream(iceStreamName);
        iceStream.addPairChangeListener(iceStreamPairChangeListener);

        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(EventFactory.transportCreated(this));
        }
    }

    /**
     * Adds to {@link #iceAgent} the
     * {@link org.ice4j.ice.harvest.CandidateHarvester} instances managed by
     * jitsi-videobridge (the TCP and SinglePort harvesters), and configures the
     * use of the dynamic host harvester.
     *
     * @param iceAgent the {@link Agent} that we'd like to append new harvesters
     * to.
     * @param rtcpmux whether rtcpmux will be used by this
     * <tt>IceUdpTransportManager</tt>.
     */
    private void configureHarvesters(Agent iceAgent, boolean rtcpmux)
    {
        ConfigurationService cfg
            = ServiceUtils.getService(
                    getBundleContext(),
                    ConfigurationService.class);
        boolean disableDynamicHostHarvester = false;

        useComponentSocket
                = cfg.getBoolean(USE_COMPONENT_SOCKET_PNAME, useComponentSocket);
        logger.info("Using component socket: " + useComponentSocket);

        iceUfragPrefix = cfg.getString(ICE_UFRAG_PREFIX_PNAME, null);
        String strategyName = cfg.getString(KEEP_ALIVE_STRATEGY_PNAME);
        KeepAliveStrategy strategy
                = KeepAliveStrategy.fromString(strategyName);
        if (strategyName != null && strategy == null)
        {
            logger.warn("Invalid keep alive strategy name: "
                    + strategyName);
        }
        else if (strategy != null)
        {
            keepAliveStrategy = strategy;
        }

        if (rtcpmux)
        {
            // TODO CandidateHarvesters may take (non-trivial) time to
            // initialize so initialize them as soon as possible, don't wa it to
            // initialize them after a Channel is requested.
            // XXX Unfortunately, TcpHarvester binds to specific local addresses
            // while Jetty binds to all/any local addresses and, consequently,
            // the order of the binding is important at the time of this
            // writing. That's why TcpHarvester is left to initialize as late as
            // possible right now.
            Harvesters.initializeStaticConfiguration(cfg);

            if (Harvesters.tcpHarvester != null)
            {
                iceAgent.addCandidateHarvester(Harvesters.tcpHarvester);
            }
            if (Harvesters.singlePortHarvesters != null)
            {
                for (CandidateHarvester harvester : Harvesters.singlePortHarvesters)
                {
                    iceAgent.addCandidateHarvester(harvester);
                    disableDynamicHostHarvester = true;
                }
            }
        }

        // Disable dynamic ports (UDP) if we're using "single port" (UPD), as
        // there's no need for a client to try a similar UDP candidate twice.
        if (disableDynamicHostHarvester)
        {
            iceAgent.setUseHostHarvester(false);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close()
    {
        if (!closed)
        {
            // Set this early to prevent double closing when the last channel
            // is removed.
            closed = true;

            if (iceStream != null)
            {
                iceStream.removePairStateChangeListener(
                        iceStreamPairChangeListener);
            }
            if (iceAgent != null)
            {
                iceAgent.free();
                iceAgent = null;
            }

            super.close();
        }
    }

    /**
     * Initializes a new <tt>Agent</tt> instance which implements the ICE
     * protocol and which is to be used by this instance to implement the Jingle
     * ICE-UDP transport.
     *
     * @param controlling
     * @param iceStreamName
     * @param rtcpmux
     * @return a new <tt>Agent</tt> instance which implements the ICE protocol
     * and which is to be used by this instance to implement the Jingle ICE-UDP
     * transport
     * @throws IOException if initializing a new <tt>Agent</tt> instance for the
     * purposes of this <tt>TransportManager</tt> fails
     */
    private Agent createIceAgent(boolean controlling,
                                 String iceStreamName,
                                 boolean rtcpmux)
            throws IOException
    {
        Agent iceAgent = new Agent(logger.getLevel(), iceUfragPrefix);

        //add videobridge specific harvesters such as a mapping and an Amazon
        //AWS EC2 harvester
        configureHarvesters(iceAgent, rtcpmux);
        iceAgent.setControlling(controlling);
        iceAgent.setPerformConsentFreshness(true);

        int portBase = portTracker.getPort();

        IceMediaStream iceStream = iceAgent.createMediaStream(iceStreamName);

        iceAgent.createComponent(
                iceStream, Transport.UDP,
                portBase, portBase, portBase + 100,
                keepAliveStrategy,
                useComponentSocket);

//         if (numComponents > 1)
//         {
//             iceAgent.createComponent(
//                     iceStream, Transport.UDP,
//                     portBase + 1, portBase + 1, portBase + 101,
//                     keepAliveStrategy,
//                     useComponentSocket);
//         }

        // Attempt to minimize subsequent bind retries: see if we have allocated
        // any ports from the dynamic range, and if so update the port tracker.
        // Do NOT update the port tracker with non-dynamic ports (e.g. 4443
        // coming from TCP) because this will force it to revert back it its
        // configured min port. When maxPort is reached, allocation will begin
        // from minPort again, so we don't have to worry about wraps.
        int maxAllocatedPort
            = TransportUtils.getMaxAllocatedPort(
                    iceStream,
                    portTracker.getMinPort(),
                    portTracker.getMaxPort());
        if (maxAllocatedPort > 0)
        {
            int nextPort = 1 + maxAllocatedPort;
            portTracker.setNextPort(nextPort);
            if (logger.isDebugEnabled())
            {
                logger.debug("Updating the port tracker min port: " + nextPort);
            }
        }

        return iceAgent;
    }

    /**
     * @return the URL to advertise for COLIBRI WebSocket connections for this
     * transport manager.
     */
    private String getColibriWsUrl()
    {
        BundleContext bundleContext
            = getConference().getVideobridge().getBundleContext();
        ColibriWebSocketService colibriWebSocketService
            = ServiceUtils.getService(
                    bundleContext, ColibriWebSocketService.class);
        if (colibriWebSocketService != null)
        {
            return colibriWebSocketService.getColibriWebSocketUrl(
                getConference().getID(),
                id,
                iceAgent.getLocalPassword());
        }

        return null;
    }

    /**
     * Gets the <tt>Conference</tt> object that this <tt>TransportManager</tt>
     * is associated with.
     */
    protected Conference getConference()
    {
        return conference;
    }

    /**
     * Gets the ICE local username fragment.
     */
    public String getLocalUfrag()
    {
        Agent iceAgent = this.iceAgent;
        return iceAgent == null ? null : iceAgent.getLocalUfrag();
    }

    /**
     * Gets the ICE password.
     */
    public String getIcePassword()
    {
        Agent iceAgent = this.iceAgent;
        return iceAgent == null ? null : iceAgent.getLocalPassword();
    }

    /**
     * Gets the <tt>BundleContext</tt> associated with the <tt>Channel</tt>
     * that this {@link net.java.sip.communicator.service.protocol.media
     * .TransportManager} is servicing. The method is a
     * convenience which gets the <tt>BundleContext</tt> associated with the
     * XMPP component implementation in which the <tt>Videobridge</tt>
     * associated with this instance is executing.
     *
     * @return the <tt>BundleContext</tt> associated with this
     * <tt>IceUdpTransportManager</tt>
     */
    public BundleContext getBundleContext()
    {
        return conference != null ? conference.getBundleContext() : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getXmlNamespace()
    {
        return IceUdpTransportPacketExtension.NAMESPACE;
    }

    /**
     * Notifies this instance about a change of the value of a property of a
     * <tt>CandidatePair</tt> of {@link #iceStream}.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the
     * <tt>CandidatePair</tt>, the name of the <tt>CandidatePair</tt> property,
     * and its old and new values
     */
    private void iceStreamPairChange(PropertyChangeEvent ev)
    {
        if (IceMediaStream.PROPERTY_PAIR_CONSENT_FRESHNESS_CHANGED.equals(
                ev.getPropertyName()))
        {
            //TODO(brian): touch activity in new scheme here
            // TODO we might not necessarily want to keep all channels alive by
            // the ICE connection.
//            getChannels().forEach(
//                channel -> channel.touch(Channel.ActivityType.TRANSPORT));
        }
    }

    /**
     * The name of the property which controls whether health checks failures
     * should be permanent. If this is set to true and the bridge fails its
     * health check once, it will not go back to the healthy state.
     */
    private static final String PERMANENT_FAILURE_PNAME
        = Health.class.getName() + ".PERMANENT_FAILURE";

    private static BundleContext bundleContext = null;
    private static boolean permanentFailureMode = false;

    private void setRtcpmux(IceUdpTransportPacketExtension transport)
    {
        //TODO(brian): need to reimeplement this if we're going to support non-rtcpmux
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConnected()
    {
        return iceConnected;
    }
}
