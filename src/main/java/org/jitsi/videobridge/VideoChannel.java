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
import java.lang.ref.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import javax.media.rtp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.ratecontrol.*;
import org.jitsi.videobridge.rtcp.*;
import org.jitsi.videobridge.simulcast.*;
import org.jitsi.videobridge.transform.*;
import org.json.simple.*;

/**
 * Implements an <tt>RtpChannel</tt> with <tt>MediaType.VIDEO</tt>.
 *
 * @author Lyubomir Marinov
 * @author George Politis
 */
public class VideoChannel
    extends RtpChannel
    implements NACKListener
{
    /**
     * The length in milliseconds of the interval for which the average incoming
     * bitrate for this video channel will be computed and made available
     * through {@link #getIncomingBitrate}.
     */
    private static final int INCOMING_BITRATE_INTERVAL_MS = 5000;

    /**
     * The name of the property which specifies the FQN name of the RTCP
     * strategy to use by default.
     */
    public static final String RTCP_TERMINATION_STRATEGY_PNAME
        = "org.jitsi.videobridge.rtcp.strategy";

    /**
     * The name of the property which specifies the simulcast mode of a
     * <tt>VideoChannel</tt>.
     */
    public static final String SIMULCAST_MODE_PNAME
        = "org.jitsi.videobridge.VideoChannel.simulcastMode";

    /**
     * The name of the property used to disable NACK termination.
     */
    public static final String DISABLE_NACK_TERMINATION_PNAME
            = "org.jitsi.videobridge.DISABLE_NACK_TERMINATION";

    /**
     * The <tt>Logger</tt> used by the <tt>VideoChannel</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(VideoChannel.class);

    /**
     * The <tt>SimulcastMode</tt> for this <tt>VideoChannel</tt>.
     */
    private SimulcastMode simulcastMode;

    /**
     * Updates the values of the property <tt>inLastN</tt> of all
     * <tt>VideoChannel</tt>s in the <tt>Content</tt> of a specific
     * <tt>VideoChannel</tt>.
     *
     * @param cause the <tt>VideoChannel</tt> which has caused the update and
     * which defines the <tt>Content</tt> to update
     */
    private static void updateInLastN(VideoChannel cause)
    {
        Channel[] channels = cause.getContent().getChannels();

        for (Channel channel : channels)
        {
            if (channel instanceof VideoChannel)
            {
                try
                {
                    ((VideoChannel) channel).updateInLastN(channels);
                }
                catch (Throwable t)
                {
                    if (t instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    else if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                    else
                        logger.error(t);
                }
            }
        }
    }

    /**
     * Whether or not to use adaptive lastN.
     */
    private boolean adaptiveLastN = false;

    /**
     * Whether or not to use adaptive simulcast.
     */
    private boolean adaptiveSimulcast = false;

    /**
     * The <tt>BitrateController</tt> which will be controlling the
     * value of <tt>bitrate</tt> for this <tt>VideoChannel</tt>.
     */
    private BitrateController bitrateController;

    /**
     * The instance which will be computing the incoming bitrate for this
     * <tt>VideoChannel</tt>.
     */
    private RateStatistics incomingBitrate
        = new RateStatistics(INCOMING_BITRATE_INTERVAL_MS, 8000F);

    /**
     * The indicator which determines whether this <tt>VideoChannel</tt> is in
     * any <tt>VideoChannel</tt>/<tt>Endpoint</tt>'s <tt>lastN</tt>.
     */
    private final AtomicBoolean inLastN = new AtomicBoolean(true);

    /**
     * The maximum number of video RTP stream to be sent from Jitsi Videobridge
     * to the endpoint associated with this video <tt>Channel</tt>.
     */
    private Integer lastN;

    /**
     * The <tt>Endpoint</tt>s in the multipoint conference in which this
     * <tt>Channel</tt> is participating ordered by
     * {@link #conferenceSpeechActivity} and used by this <tt>Channel</tt> for
     * the support of {@link #lastN}.
     */
    private List<WeakReference<Endpoint>> lastNEndpoints;

    /**
     * The <tt>Object</tt> which synchronizes the access to
     * {@link #lastNEndpoints} and {@link #lastN}.
     */
    private final ReadWriteLock lastNSyncRoot = new ReentrantReadWriteLock();

    /**
     * Whether the bridge should request retransmissions for missing packets
     * on this channel.
     */
    private final boolean requestRetransmissions;

    /**
     * Initializes a new <tt>VideoChannel</tt> instance which is to have a
     * specific ID. The initialization is to be considered requested by a
     * specific <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>VideoChannel</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     * @param transportNamespace the namespace of transport used by this
     * channel. Can be either {@link IceUdpTransportPacketExtension#NAMESPACE}
     * or {@link RawUdpTransportPacketExtension#NAMESPACE}.
     * @param initiator the value to use for the initiator field, or
     * <tt>null</tt> to use the default value.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public VideoChannel(Content content,
                        String id,
                        String channelBundleId,
                        String transportNamespace,
                        Boolean initiator)
        throws Exception
    {
        super(content, id, channelBundleId, transportNamespace, initiator);

        setTransformEngine(new RtpChannelTransformEngine(this));

        ConfigurationService cfg
            = content.getConference().getVideobridge()
                        .getConfigurationService();
        requestRetransmissions
            = (cfg != null && cfg.getBoolean(
                        VideoMediaStream.REQUEST_RETRANSMISSIONS_PNAME, false));
    }

    /**
     * {@inheritDoc}
     *
     * Creates media stream.
     */
    @Override
    public void initialize()
        throws IOException
    {
        super.initialize();

        ConfigurationService cfg
            = getContent().getConference().getVideobridge()
                .getConfigurationService();

        if (cfg == null)
        {
            return;
        }

        // Initialize the RTCP termination strategy from the configuration.
        String strategyFQN = cfg.getString(RTCP_TERMINATION_STRATEGY_PNAME, "");

        if (StringUtils.isNullOrEmpty(strategyFQN))
        {
            return;
        }

        RTCPTerminationStrategy strategy;

        try
        {
            strategy
                = (RTCPTerminationStrategy)
                    Class.forName(strategyFQN).newInstance();
        }
        catch (Exception e)
        {
            logger.error(
                "Failed to configure the video channel RTCP termination"
                    + " strategy.",
                e);

            return;
        }

        MediaStream stream = getStream();

        // Initialize the RTCP termination strategy.
        if (strategy instanceof VideoChannelRTCPTerminationStrategy)
        {
            ((VideoChannelRTCPTerminationStrategy) strategy).initialize(this);
        }

        stream.setRTCPTerminationStrategy(strategy);

        boolean enableNackTermination
                = !cfg.getBoolean(DISABLE_NACK_TERMINATION_PNAME, false);
        if (enableNackTermination)
        {
            RawPacketCache cache = stream.getPacketCache();
            if (cache != null)
            {
                cache.setEnabled(true);
            }
            else
            {
                logger.warn("NACK termination is enabled, but we don't have" +
                                    " a packet cache.");
            }

            stream.getMediaStreamStats().addNackListener(this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p)
    {
        boolean accept = super.acceptDataInputStreamDatagramPacket(p);

        if (accept)
        {
            // TODO: find a way to do this only in case it is actually needed
            // (currently this means when there is another channel in the
            // same content, with adaptive-last-n turned on), in order to not
            // waste resources.
            // XXX should we also count bytes received for RTCP towards the
            // incoming bitrate?
            incomingBitrate.update(p.getLength(), System.currentTimeMillis());
        }

        return accept;
    }

    /**
     * Performs (additional) <tt>VideoChannel</tt>-specific configuration of the
     * <tt>TransformEngineChain</tt> employed by the <tt>MediaStream</tt> of
     * this <tt>RtpChannel</tt>.
     *
     * @param chain the <tt>TransformEngineChain</tt> employed by the
     * <tt>MediaStream</tt> of this <tt>RtpChannel</tt>
     */
    private void configureTransformEngineChain(TransformEngineChain chain)
    {
        // Make sure there is a LastNTransformEngine in the TransformEngineChain
        // in order optimize the performance by dropping received RTP packets
        // from VideoChannels/Endpoints which are not in any
        // VideoChannel/Endpoint's lastN.
        TransformEngine[] engines = chain.getEngineChain();
        boolean add = true;

        if ((engines != null) && (engines.length != 0))
        {
            for (TransformEngine engine : engines)
            {
                if (engine instanceof LastNTransformEngine)
                {
                    add = false;
                    break;
                }
            }
        }
        if (add)
            chain.addEngine(new LastNTransformEngine(this));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;

        super.describe(iq);

        iq.setLastN(lastN);

        SimulcastMode simulcastMode = getSimulcastMode();

        iq.setSimulcastMode(simulcastMode);
    }

    /**
     * Gets a boolean value indicating whether or not to use adaptive lastN.
     *
     * @return a boolean value indicating whether or not to use adaptive lastN.
     */
    public boolean getAdaptiveLastN()
    {
        return this.adaptiveLastN;
    }

    /**
     * Gets a boolean value indicating whether or not to use adaptive simulcast.
     *
     * @return a boolean value indicating whether or not to use adaptive
     * simulcast.
     */
    public boolean getAdaptiveSimulcast()
    {
        return this.adaptiveSimulcast;
    }

    /**
     * Returns the <tt>BitrateController</tt> for this <tt>VideoChannel</tt>,
     * creating it if necessary.
     *
     * @return the <tt>VideoChannelLastNAdaptor</tt> for this
     * <tt>VideoChannel</tt>, creating it if necessary.
     */
    private BitrateController getBitrateController()
    {
        if (bitrateController == null)
            bitrateController = new BitrateController(this);
        return bitrateController;
    }

    /**
     * Returns the current incoming bitrate in bits per second for this
     * <tt>VideoChannel</tt> (computed as the average bitrate over the last
     * {@link #INCOMING_BITRATE_INTERVAL_MS} milliseconds).
     *
     * @return the current incoming bitrate for this <tt>VideoChannel</tt>.
     */
    public long getIncomingBitrate()
    {
        return incomingBitrate.getRate(System.currentTimeMillis());
    }

    /**
     * Gets the maximum number of video RTP streams to be sent from Jitsi
     * Videobridge to the endpoint associated with this video <tt>Channel</tt>.
     *
     * @return the maximum number of video RTP streams to be sent from Jitsi
     * Videobridge to the endpoint associated with this video <tt>Channel</tt>.
     * If no value or <tt>null</tt> has been explicitly set or this is not a
     * video <tt>Channel</tt>, returns <tt>-1</tt>.
     */
    public int getLastN()
    {
        Integer lastNInteger = this.lastN;

        return (lastNInteger == null) ? -1 : lastNInteger.intValue();
    }

    /**
     * Returns the list of <tt>Endpoint</tt>s for the purposes of
     * &quot;last N&quot;.
     *
     * @return the list of <tt>Endpoint</tt>s for the purposes of
     * &quot;last N&quot;
     */
    public List<Endpoint> getLastNEndpoints()
    {
        Lock readLock = lastNSyncRoot.readLock();
        List<Endpoint> endpoints;

        readLock.lock();
        try
        {
            if (lastNEndpoints == null || lastNEndpoints.isEmpty())
            {
                endpoints = Collections.emptyList();
            }
            else
            {
                endpoints = new ArrayList<Endpoint>(lastNEndpoints.size());
                for (WeakReference<Endpoint> wr : lastNEndpoints)
                {
                    Endpoint endpoint = wr.get();

                    if (endpoint != null)
                        endpoints.add(endpoint);
                }
            }
        }
        finally
        {
            readLock.unlock();
        }

        return endpoints;
    }

    private Endpoint getEffectivePinnedEndpoint()
    {
        // For the purposes of LastN, we consider that the user has no pinned
        // participant if he/she has pinned himself/herself.
        Endpoint thisEndpoint = getEndpoint();
        Endpoint pinnedEndpoint = thisEndpoint.getPinnedEndpoint();
        return thisEndpoint != null && !thisEndpoint.equals(pinnedEndpoint)
            ? pinnedEndpoint : null;
    }

    public int getReceivingEndpointCount()
    {
        int receivingEndpointCount;

        if (getLastN() == -1)
        {
            // LastN is disabled. Consequently, this endpoint receives all the
            // other participants.
            receivingEndpointCount
                = getContent().getConference().getEndpointCount();
        }
        else
        {
            // LastN is enabled. Get the last N endpoints that this endpoint is
            // receiving.
            receivingEndpointCount = getLastNEndpoints().size();
        }
        return receivingEndpointCount;
    }

    /**
     * Creates and returns an iterator of the endpoints that are currently
     * being received by this channel.
     *
     * @return an iterator of the endpoints that are currently being received
     * by this channel.
     */
    public Iterator<Endpoint> getReceivingEndpoints()
    {
        final List<Endpoint> endpoints;

        if (getLastN() == -1)
        {
            // LastN is disabled. Consequently, this endpoint receives all the
            // other participants.
            endpoints = getContent().getConference().getEndpoints();
        }
        else
        {
            // LastN is enabled. Get the last N endpoints that this endpoint is
            // receiving.
            endpoints = getLastNEndpoints();
        }

        final int lastIx = endpoints.size() - 1;

        return
            new Iterator<Endpoint>()
            {
                private int ix = 0;

                @Override
                public boolean hasNext()
                {
                    return ix <= lastIx;
                }

                @Override
                public Endpoint next()
                {
                    if (hasNext())
                        return endpoints.get(ix++);
                    else
                        throw new NoSuchElementException();
                }

                @Override
                public void remove()
                {
                    throw new UnsupportedOperationException();
                }
            };
    }

    /**
     * Notifies this <tt>VideoChannel</tt> that the value of its property
     * <tt>inLastN</tt> has changed from <tt>oldValue</tt> to <tt>newValue</tt>.
     *
     * @param oldValue the old value of the property <tt>inLastN</tt> before the
     * change
     * @param newValue the new value of the property <tt>inLastN</tt> after the
     * change
     */
    private void inLastNChanged(boolean oldValue, boolean newValue)
    {
        Endpoint endpoint = getEndpoint();

        if (endpoint != null)
        {
            try
            {
                endpoint.sendMessageOnDataChannel(
                        "{\"colibriClass\":\"InLastNChangeEvent\",\"oldValue\":"
                            + oldValue + ",\"newValue\":" + newValue + "}");
            }
            catch (IOException ex)
            {
                logger.error("Failed to send message on data channel.", ex);
            }
        }
    }

    /**
     * Determines whether this <tt>VideoChannel</tt> is in any
     * <tt>VideoChannel</tt>/<tt>Endpoint</tt>'s <tt>lastN</tt>.
     *
     * @return <tt>true</tt> if the RTP streams received by this
     * <tt>VideoChannel</tt> are to be sent to its remote endpoints; otherwise,
     * <tt>false</tt>
     */
    public boolean isInLastN()
    {
        return inLastN.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInLastN(Channel channel)
    {
        int lastN = getLastN();

        if (lastN < 0)
            return true;

        Endpoint channelEndpoint = channel.getEndpoint();

        if (channelEndpoint == null)
            return true;

        ConferenceSpeechActivity conferenceSpeechActivity
            = this.conferenceSpeechActivity;

        if (conferenceSpeechActivity == null)
            return true;
        if (lastN == 0)
            return false;

        // We do not hold any lock on lastNSyncRoot here because it should be OK
        // for multiple threads to check whether lastNEndpoints is null and
        // invoke the method to populate it because (1) the method to populate
        // lastNEndpoints will acquire the necessary locks to ensure preserving
        // the correctness of the state of this instance under the conditions of
        // concurrent access and (2) we do not want to hold a write lock on
        // lastNSyncRoot while invoking the method to populate lastNEndpoints
        // because the latter might fire an event.
        if (lastNEndpoints == null)
        {
            // Pretend that the ordered list of Endpoints maintained by
            // conferenceSpeechActivity has changed in order to populate
            // lastNEndpoints.
            speechActivityEndpointsChanged(null);
        }

        Lock readLock = lastNSyncRoot.readLock();
        boolean inLastN = false;

        readLock.lock();
        try
        {
            if (lastNEndpoints != null)
            {
                int n = 0;
                // The pinned endpoint is always in the last N set, if
                // last N > 0.
                Endpoint pinnedEndpoint = getEffectivePinnedEndpoint();
                // Keep one empty slot for the pinned endpoint.
                int nMax = (pinnedEndpoint == null) ? lastN : (lastN - 1);
                Endpoint thisEndpoint = getEndpoint();

                for (WeakReference<Endpoint> wr : lastNEndpoints)
                {
                    if (n >= nMax)
                        break;

                    Endpoint e = wr.get();

                    if (e != null)
                    {
                        if (e.equals(thisEndpoint))
                        {
                            continue;
                        }
                        else if (e.equals(channelEndpoint))
                        {
                            inLastN = true;
                            break;
                        }
                    }

                    ++n;
                }

                // FIXME(gp) move this if before the for loop (to avoid an
                // unnecessary loop)
                if (!inLastN && pinnedEndpoint != null)
                    inLastN = channelEndpoint == pinnedEndpoint;
            }
        }
        finally
        {
            readLock.unlock();
        }
        return inLastN;
    }

    /**
     * Notifies this instance that the list of <tt>Endpoint</tt>s defined by
     * {@link #lastN} has changed.
     *
     * @param endpointsEnteringLastN the <tt>Endpoint</tt>s which are entering
     * the list of <tt>Endpoint</tt>s defined by <tt>lastN</tt>
     */
    private void lastNEndpointsChanged(List<Endpoint> endpointsEnteringLastN)
    {
        try
        {
            sendLastNEndpointsChangeEventOnDataChannel(endpointsEnteringLastN);
        }
        finally
        {
            updateInLastN(this);
        }
    }

    /**
     * Gets the index of a specific <tt>Endpoint</tt> in a specific list of
     * <tt>lastN</tt> <tt>Endpoint</tt>s.
     *
     * @param endpoints the list of <tt>Endpoint</tt>s into which to look for
     * <tt>endpoint</tt>
     * @param lastN the number of <tt>Endpoint</tt>s in <tt>endpoint</tt>s to
     * look through
     * @param endpoint the <tt>Endpoint</tt> to find within <tt>lastN</tt>
     * elements of <tt>endpoints</tt>
     * @return the <tt>lastN</tt> index of <tt>endpoint</tt> in
     * <tt>endpoints</tt> or <tt>-1</tt> if <tt>endpoint</tt> is not within the
     * <tt>lastN</tt> elements of <tt>endpoints</tt>
     */
    private int lastNIndexOf(
            List<Endpoint> endpoints,
            int lastN,
            Endpoint endpoint)
    {
        Endpoint thisEndpoint = getEndpoint();
        int n = 0;

        for (Endpoint e : endpoints)
        {
            if (n >= lastN)
                break;

            if (e.equals(thisEndpoint))
                continue;
            else if (e.equals(endpoint))
                return n;

            ++n;
        }
        return -1;
    }

    @Override
    public void propertyChange(PropertyChangeEvent ev)
    {
        super.propertyChange(ev);

        String propertyName = ev.getPropertyName();

        if (Endpoint.PINNED_ENDPOINT_PROPERTY_NAME.equals(propertyName))
        {
            // The pinned endpoint is always in the last N set, if last N > 0.
            // So, it (the pinned endpoint) has changed, the lastN has changed.
            if (this.getLastN() < 1)
            {
                return;
            }

            // Pretend that the ordered list of Endpoints maintained by
            // conferenceSpeechActivity has changed in order to populate
            // lastNEndpoints and get the channel endpoints to ask for key
            // frames.
            List<Endpoint> channelEndpointsToAskForKeyframes
                    = speechActivityEndpointsChanged(null, true);

            if ((channelEndpointsToAskForKeyframes != null)
                    && !channelEndpointsToAskForKeyframes.isEmpty())
            {
                getContent().askForKeyframes(channelEndpointsToAskForKeyframes);
            }
        }
        else if (Content.CHANNEL_MODIFIED_PROPERTY_NAME.equals(propertyName))
        {
            // Another channel in this content has been modified (
            // added/removed/modified source group, same with payload types, etc)
            // This has implications in SSRC rewriting, we need to update our
            // engine.
            logDebug("Handling CHANNEL_MODIFIED_PROPERTY_NAME");
            VideoChannel videoChannel = (VideoChannel) ev.getNewValue();
            updateTranslatedVideoChannel(videoChannel);
        }
    }

    /**
     * Notifies this <tt>VideoChannel</tt> that an RTCP REMB packet with a
     * bitrate value of <tt>bitrateBps</tt> bits per second was received.
     *
     * @param bitrateBps the bitrate of the received REMB packet in bits per
     * second.
     */
    public void handleREMB(long bitrateBps)
    {
        BitrateController bc = getBitrateController();

        if (bc != null)
            bc.receivedREMB(bitrateBps);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean rtpTranslatorWillWrite(
            boolean data,
            byte[] buffer, int offset, int length,
            Channel source)
    {
        boolean accept = true;

        if (data && (source != null))
        {
            // XXX(gp) we could potentially move this into a TransformEngine.
            accept = isInLastN(source);
        }

        return accept;
    }

    /**
     * {@inheritDoc}
     *
     * Fires initial events over the WebRTC data channel of this
     * <tt>VideoChannel</tt> such as the list of last-n <tt>Endpoint</tt>s whose
     * video is sent/RTP translated by this <tt>RtpChannel</tt> to its
     * <tt>Endpoint</tt>.
     */
    @Override
    void sctpConnectionReady(Endpoint endpoint)
    {
        super.sctpConnectionReady(endpoint);

        if (endpoint.equals(getEndpoint()))
            lastNEndpointsChanged(null);
    }

    /**
     * Sends a message with <tt>colibriClass</tt>
     * <tt>LastNEndpointsChangeEvent</tt> to the <tt>Endpoint</tt> of this
     * <tt>VideoChannel</tt> in order to notify it that the list/set of
     * <tt>lastN</tt> has changed.
     *
     * @param endpointsEnteringLastN the <tt>Endpoint</tt>s which are entering
     * the list of <tt>Endpoint</tt>s defined by <tt>lastN</tt>
     */
    private void sendLastNEndpointsChangeEventOnDataChannel(
            List<Endpoint> endpointsEnteringLastN)
    {
        int lastN = getLastN();

        if (lastN < 0)
            return;

        Endpoint thisEndpoint = getEndpoint();

        if (thisEndpoint == null)
            return;

        // Represent the list of Endpoints defined by lastN in JSON format.
        Lock readLock = lastNSyncRoot.readLock();
        StringBuilder lastNEndpointsStr = new StringBuilder();
        // We want endpointsEnteringLastN to always to reported. Consequently,
        // we will pretend that all lastNEndpoints are entering if no explicit
        // endpointsEnteringLastN is specified.
        List<Endpoint> effectiveEndpointsEnteringLastN = endpointsEnteringLastN;

        if (effectiveEndpointsEnteringLastN == null)
            effectiveEndpointsEnteringLastN = new ArrayList<Endpoint>(lastN);

        // The pinned endpoint is always in the last N set, if last N > 0.
        Endpoint pinnedEndpoint = getEffectivePinnedEndpoint();

        readLock.lock();
        try
        {
            if ((lastNEndpoints != null) && !lastNEndpoints.isEmpty())
            {
                int n = 0;
                boolean foundPinnedEndpoint = pinnedEndpoint == null;

                for (WeakReference<Endpoint> wr : lastNEndpoints)
                {
                    if (n >= lastN)
                        break;
                    Endpoint e = wr.get();

                    // The pinned endpoint is always in the last N set, if
                    // last N > 0.
                    if (!foundPinnedEndpoint)
                    {
                        if (n == lastN - 1)
                            e = pinnedEndpoint;
                        else
                            foundPinnedEndpoint = e == pinnedEndpoint;
                    }

                    if (e != null)
                    {
                        if (e.equals(thisEndpoint))
                        {
                            continue;
                        }
                        else
                        {
                            if (lastNEndpointsStr.length() != 0)
                                lastNEndpointsStr.append(',');
                            lastNEndpointsStr.append('"');
                            lastNEndpointsStr.append(
                                    JSONValue.escape(e.getID()));
                            lastNEndpointsStr.append('"');

                            if (effectiveEndpointsEnteringLastN
                                    != endpointsEnteringLastN)
                            {
                                effectiveEndpointsEnteringLastN.add(e);
                            }
                        }
                    }

                    ++n;
                }
            }
        }
        finally
        {
            readLock.unlock();
        }

        // colibriClass
        StringBuilder msg
            = new StringBuilder(
                    "{\"colibriClass\":\"LastNEndpointsChangeEvent\"");

        // lastNEndpoints
        msg.append(",\"lastNEndpoints\":[");
        msg.append(lastNEndpointsStr);
        msg.append(']');

        // endpointsEnteringLastN

        // We want endpointsEnteringLastN to always to reported. Consequently,
        // we will pretend that all lastNEndpoints are entering if no explicit
        // endpointsEnteringLastN is specified.
        endpointsEnteringLastN = effectiveEndpointsEnteringLastN;
        if ((endpointsEnteringLastN != null)
                && !endpointsEnteringLastN.isEmpty())
        {
            StringBuilder endpointsEnteringLastNStr = new StringBuilder();

            for (Endpoint e : endpointsEnteringLastN)
            {
                if (endpointsEnteringLastNStr.length() != 0)
                    endpointsEnteringLastNStr.append(',');
                endpointsEnteringLastNStr.append('"');
                endpointsEnteringLastNStr.append(
                        JSONValue.escape(e.getID()));
                endpointsEnteringLastNStr.append('"');
            }
            if (endpointsEnteringLastNStr.length() != 0)
            {
                msg.append(",\"endpointsEnteringLastN\":[");
                msg.append(endpointsEnteringLastNStr);
                msg.append(']');
            }
        }

        msg.append('}');
        try
        {
            thisEndpoint.sendMessageOnDataChannel(msg.toString());
        }
        catch (IOException e)
        {
            logger.error("Failed to send message on data channel.", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAdaptiveLastN(boolean adaptiveLastN)
    {
        this.adaptiveLastN = adaptiveLastN;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAdaptiveSimulcast(boolean adaptiveSimulcast)
    {
        this.adaptiveSimulcast = adaptiveSimulcast;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastN(Integer lastN)
    {
        if (this.lastN == lastN)
            return;

        // If the old value was null, even though we may detect endpoints
        // "entering" lastN, they are already being received and so no keyframes
        // are necessary.
        boolean askForKeyframes = this.lastN == null;

        Lock writeLock = lastNSyncRoot.writeLock();
        List<Endpoint> endpointsEnteringLastN = new LinkedList<Endpoint>();

        writeLock.lock();
        try
        {
            // XXX(gp) question to the lastN guru : if this.lastN == null or
            // this.lastN < 0, do we really want to call lastNEndpointsChanged
            // with an empty (but not null!!) list of endpoints?
            if (this.lastN != null && this.lastN >= 0)
            {
                if (lastN > this.lastN)
                {
                    Endpoint pinnedEndpoint = getEffectivePinnedEndpoint();
                    // The pinned endpoint is always in the last N set, if
                    // last N > 0; Count it here.
                    int n = (pinnedEndpoint != null) ? 1 : 0;
                    Endpoint thisEndpoint = getEndpoint();

                    // We do not hold any lock on lastNSyncRoot here because it
                    // should be OK for multiple threads to check whether
                    // lastNEndpoints is null and invoke the method to populate
                    // it because (1) the method to populate lastNEndpoints will
                    // acquire the necessary locks to ensure preserving the
                    // correctness of the state of this instance under the
                    // conditions of concurrent access and (2) we do not want
                    // to hold a write lock on lastNSyncRoot while invoking the
                    // method to populate lastNEndpoints because the latter
                    // might fire an event.
                    if (lastNEndpoints == null)
                    {
                        // Pretend that the ordered list of Endpoints maintained
                        // by conferenceSpeechActivity has changed in order to
                        // populate lastNEndpoints.
                        speechActivityEndpointsChanged(null);
                    }

                    if (lastNEndpoints != null)
                    {
                        for (WeakReference<Endpoint> wr : lastNEndpoints)
                        {
                            if (n >= lastN)
                                break;

                            Endpoint endpoint = wr.get();

                            if (endpoint != null)
                            {
                                if (endpoint.equals(thisEndpoint))
                                    continue;

                                // We've already signaled to the client the fact
                                // that the pinned endpoint has entered the
                                // lastN set when we handled the
                                //
                                //     PINNED_ENDPOINT_PROPERTY_NAME
                                //
                                // property change event.
                                //
                                // Also, we've already counted it above. So, we
                                // don't want to either add it in the
                                // endpointsEnteringLastN or count it here.

                                if (endpoint.equals(pinnedEndpoint))
                                    continue;
                            }

                            ++n;
                            if (n > this.lastN && endpoint != null)
                                endpointsEnteringLastN.add(endpoint);
                        }
                    }
                }
            }

            this.lastN = lastN;
        }
        finally
        {
            writeLock.unlock();
        }

        lastNEndpointsChanged(endpointsEnteringLastN);

        if (askForKeyframes)
        {
            getContent().askForKeyframes(
                    new HashSet<Endpoint>(endpointsEnteringLastN));
        }

        touch(); // It seems this Channel is still active.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    List<Endpoint> speechActivityEndpointsChanged(List<Endpoint> endpoints)
    {
        return speechActivityEndpointsChanged(endpoints, false);
    }

    private List<Endpoint> speechActivityEndpointsChanged(
            List<Endpoint> endpoints, boolean pinnedEndpointChanged)
    {
        Lock writeLock = lastNSyncRoot.writeLock();
        List<Endpoint> endpointsEnteringLastN = null;
        boolean lastNEndpointsChanged = pinnedEndpointChanged;

        writeLock.lock();
        try
        {
            // Determine which Endpoints are entering the list of lastN.
            int lastN = getLastN();

            if (endpoints == null)
            {
                endpoints = conferenceSpeechActivity.getEndpoints();
            }
            if (lastN >= 0)
            {
                Endpoint thisEndpoint = getEndpoint();

                // At most the first lastN are entering the list of lastN.
                endpointsEnteringLastN = new ArrayList<Endpoint>(lastN);

                // The pinned endpoint is always in the last N set, if
                // last N > 0.
                Endpoint pinnedEndpoint = getEffectivePinnedEndpoint();

                if (pinnedEndpoint != null && lastN > 0)
                    endpointsEnteringLastN.add(pinnedEndpoint);

                for (Endpoint e : endpoints)
                {
                    if (endpointsEnteringLastN.size() >= lastN)
                        break;
                    if (!e.equals(thisEndpoint) && !e.equals(pinnedEndpoint))
                        endpointsEnteringLastN.add(e);
                }

                if (lastNEndpoints != null && !lastNEndpoints.isEmpty())
                {
                    // Some of these first lastN are already in the list of
                    // lastN.
                    int n = 0;

                    for (WeakReference<Endpoint> wr : lastNEndpoints)
                    {
                        if (n >= lastN)
                            break;

                        Endpoint e = wr.get();

                        if (e != null)
                        {
                            if (e.equals(thisEndpoint))
                            {
                                continue;
                            }
                            else
                            {
                                endpointsEnteringLastN.remove(e);
                                if (lastNIndexOf(endpoints, lastN, e) < 0)
                                    lastNEndpointsChanged = true;
                            }
                        }

                        ++n;
                    }
                }
            }

            // Remember the Endpoints for the purposes of lastN.
            lastNEndpoints
                = new ArrayList<WeakReference<Endpoint>>(endpoints.size());
            for (Endpoint endpoint : endpoints)
                lastNEndpoints.add(new WeakReference<Endpoint>(endpoint));
        }
        finally
        {
            writeLock.unlock();
        }

        if (endpointsEnteringLastN != null
                && !endpointsEnteringLastN.isEmpty())
        {
            lastNEndpointsChanged = true;
        }

        // Notify about changes in the list of lastN.
        if (lastNEndpointsChanged)
            lastNEndpointsChanged(endpointsEnteringLastN);

        // Request keyframes from the Endpoints entering the list of lastN.
        return endpointsEnteringLastN;
    }

    /**
     * {@inheritDoc}
     *
     * If <tt>newValue</tt> employs a <tt>TransformEngineChain</tt>, allows this
     * <tt>VideoChannel</tt> to configure it.
     */
    @Override
    protected void streamRTPConnectorChanged(
            RTPConnector oldValue,
            RTPConnector newValue)
    {
        super.streamRTPConnectorChanged(oldValue, newValue);

        TransformEngine engine;

        if (newValue instanceof RTPTransformTCPConnector)
            engine = ((RTPTransformTCPConnector) newValue).getEngine();
        else if (newValue instanceof RTPTransformUDPConnector)
            engine = ((RTPTransformUDPConnector) newValue).getEngine();
        else
            engine = null;
        if ((engine != null) && (engine instanceof TransformEngineChain))
            configureTransformEngineChain((TransformEngineChain) engine);
    }

    /**
     * Updates the value of the property <tt>inLastN</tt> of this
     * <tt>VideoChannel</tt>.
     *
     * @param channels the list/set of <tt>Channel</tt>s in the <tt>Content</tt>
     * of this <tt>VideoChannel</tt>. Explicitly provided in order to reduce the
     * number of allocations in particular and the consequent effects of garbage
     * collection in general.
     */
    private void updateInLastN(Channel[] channels)
    {
        boolean inLastN;

        if (channels.length == 0)
        {
            // If this VideoChannel is not within the list of Channels of its
            // associated Content, then something is amiss and we would better
            // not mess around with its received RTP packets.
            inLastN = true;
        }
        else
        {
            Endpoint endpoint = getEndpoint();

            // If videoChannel is the only Channel in its associated Content,
            // then we do NOT want to drop its received RTP packets.
            inLastN = true;
            for (Channel c : channels)
            {
                if (equals(c))
                    continue;

                // A Channel should not be forwarded to another Channel if the
                // two Channels belong to one and the same Endpoint.
                // Consequently, isInLastN is unnecessary in the case.
                if ((endpoint != null) && endpoint.equals(c.getEndpoint()))
                    continue;

                inLastN = ((VideoChannel) c).isInLastN(this);
                if (inLastN)
                    break;
            }
        }

        if (this.inLastN.compareAndSet(!inLastN, inLastN))
            inLastNChanged(!inLastN, inLastN);
    }

    /**
     *
     * @param payloadTypes the <tt>PayloadTypePacketExtension</tt>s which
     * specify the payload types (i.e. the <tt>MediaFormat</tt>s) to be used by
     */
    @Override
    public void setPayloadTypes(List<PayloadTypePacketExtension> payloadTypes)
    {
        super.setPayloadTypes(payloadTypes);

        boolean enableRedFilter = true;

        // If we're not given any PTs at all, assume that we shouldn't touch
        // RED.
        if (payloadTypes == null || payloadTypes.size() == 0)
            return;

        for (PayloadTypePacketExtension payloadTypePacketExtension
            : payloadTypes)
        {
            if (Constants.RED.equals(payloadTypePacketExtension.getName()))
            {
                enableRedFilter = false;
                break;
            }
        }

        // If the endpoint supports RED we disable the filter (e.g. leave RED).
        // Otherwise, we strip it.
        if (transformEngine != null)
            transformEngine.enableREDFilter(enableRedFilter);
    }

    /**
     * Implements {@link NACKListener#nackReceived(NACKPacket)}.
     *
     * Handles an incoming RTCP NACK packet from a receiver.
     */
    @Override
    public void nackReceived(NACKPacket nackPacket)
    {
        Set<Integer> lostPackets = new HashSet<>();
        lostPackets.addAll(nackPacket.getLostPackets());

        long ssrc = nackPacket.sourceSSRC;

        if (logger.isDebugEnabled())
        {
            logDebug("Received NACK on channel " + getID() +" for SSRC "
                                 + ssrc + ". Packets reported lost: "
                                 + lostPackets);
        }

        RawPacketCache cache = getStream().getPacketCache();
        RtxTransformer rtxTransformer = transformEngine.getRtxTransformer();
        if (cache != null && rtxTransformer != null)
        {
            Iterator<Integer> iter = lostPackets.iterator();
            while (iter.hasNext())
            {
                int seq = iter.next();
                RawPacket pkt = cache.get(ssrc, seq);
                if (pkt != null)
                {
                    if (logger.isDebugEnabled())
                    {
                        logDebug("Retransmitting packet from cache. SSRC "
                                             + ssrc + " seq " + seq);
                    }

                    if (rtxTransformer.retransmit(pkt))
                    {
                        iter.remove();
                    }
                }
            }
        }

        if (!lostPackets.isEmpty())
        {
            if (requestRetransmissions)
            {
                // If retransmission requests are enabled, videobridge assumes
                // the responsibility of requesting missing packets.
                logDebug("Packets missing from the cache. Ignoring, because"
                                     + " retransmission requests are enabled.");
            }
            else
            {
                // Otherwise, if retransmission requests are disabled, we send
                // a NACK packet of our own.
                sendNack(nackPacket.senderSSRC, ssrc, lostPackets);
            }
        }
    }

    /**
     * Creates an RTCP NACK packet with the given Packet Sender and Media Source
     * SSRCs and the given set of sequence numbers, and sends it to the
     * appropriate channels depending on the Media Source SSRC.
     * @param packetSenderSsrc the SSRC to use for the Packet Sender field.
     * @param mediaSourceSsrc the SSRC to use for the Media Source field.
     * @param seqs the set of sequence numbers to include in the NACK packet.
     */
    private void sendNack(long packetSenderSsrc,
                          long mediaSourceSsrc,
                          Set<Integer> seqs)
    {
        // Note: this does not execute when SSRC rewriting is in use, because
        // the latter depends on retransmission requests being enabled. So we
        // can send a NACK with the original SSRC and sequence numbers without
        // worrying about them not matching what the sender actually sent.
        NACKPacket newNack
            = new NACKPacket(packetSenderSsrc, mediaSourceSsrc, seqs);
        RawPacket pkt = null;
        try
        {
            pkt = newNack.toRawPacket();
        }
        catch (IOException ioe)
        {
            logWarn("Failed to create NACK packet: " + ioe);
        }

        if (pkt != null)
        {
            Set<RtpChannel> channelsToSendTo = new HashSet<RtpChannel>();
            Channel channel
                = getContent().findChannelByReceiveSSRC(mediaSourceSsrc);
            if (channel != null && channel instanceof RtpChannel)
            {
                channelsToSendTo.add((RtpChannel) channel);
            }
            else
            {
                // If searching by SSRC fails, we transmit the NACK on all
                // other channels.
                // TODO: We might want to *always* send these to all channels,
                // in order to not prevent the mechanism for avoidance of
                // retransmission of multiple RTCP FB defined in AVPF:
                // https://tools.ietf.org/html/rfc4585#section-3.2
                // This is, unless/until we implement some mechanism of our own.
                for (Channel c : getContent().getChannels())
                {
                    if (c != null && c instanceof RtpChannel && c != this)
                    {
                        channelsToSendTo.add((RtpChannel) c);
                    }
                }
            }

            for (RtpChannel c : channelsToSendTo)
            {
                if (logger.isDebugEnabled())
                {
                    logDebug("Sending a NACK for SSRC " + mediaSourceSsrc
                                         + " , packets " + seqs
                                         + " on channel " + c.getID());
                }

                try
                {
                    c.getStream().injectPacket(pkt, false, true);
                }
                catch (TransmissionFailedException e)
                {
                    logWarn("Failed to inject packet in MediaStream: " + e);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSourceGroups(List<SourceGroupPacketExtension> sourceGroups)
    {
        super.setSourceGroups(sourceGroups);

        // TODO(gp) how does one clear source groups? We need a special value
        // that indicates we need to clear the groups.
        if (sourceGroups == null || sourceGroups.size() == 0)
        {
            return;
        }

        // Setup simulcast layers from source groups.
        SimulcastEngine simulcastEngine
            = getTransformEngine().getSimulcastEngine();

        Map<Long, SimulcastLayer> ssrc2layer
            = new HashMap<Long, SimulcastLayer>();

        // Build the simulcast layers.
        SortedSet<SimulcastLayer> layers = new TreeSet<SimulcastLayer>();
        for (SourceGroupPacketExtension sourceGroup : sourceGroups)
        {
            List<SourcePacketExtension> sources = sourceGroup.getSources();

            if (sources == null || sources.isEmpty()
                || !SourceGroupPacketExtension.SEMANTICS_SIMULCAST
                        .equalsIgnoreCase(sourceGroup.getSemantics()))
            {
                continue;
            }

            // sources are in low to high order.
            int order = 0;
            for (SourcePacketExtension source : sources)
            {
                Long primarySSRC = source.getSSRC();
                SimulcastLayer simulcastLayer = new SimulcastLayer(
                    simulcastEngine.getSimulcastReceiver(),
                    primarySSRC,
                    order++);

                // Add the layer to the reverse map.
                ssrc2layer.put(primarySSRC, simulcastLayer);

                // Add the layer to the sorted set.
                layers.add(simulcastLayer);
            }

        }

        // FID groups have been saved in RtpChannel. Make sure any changes are
        // propagated to the appropriate SimulcastLayer-s.
        for (Map.Entry<Long, Long> entry : this.fidSourceGroups.entrySet())
        {
            SimulcastLayer simulcastLayer = ssrc2layer.get(entry.getKey());
            simulcastLayer.setRTXSSRC(entry.getValue());
        }

        simulcastEngine.getSimulcastReceiver().setSimulcastLayers(layers);
    }

    /**
     * Sets the <tt>SimulcastMode</tt> of this <tt>VideoChannel</tt>.
     *
     * @param newSimulcastMode the new <tt>SimulcastMode</tt> of this
     * <tt>VideoChannel</tt>.
     */
    public void setSimulcastMode(SimulcastMode newSimulcastMode)
    {
        SimulcastMode oldSimulcastMode = getSimulcastMode();
        if (oldSimulcastMode == newSimulcastMode)
        {
            return;
        }

        if (logger.isDebugEnabled())
        {
            logDebug("Setting simulcast mode to " + newSimulcastMode);
        }

        simulcastMode = newSimulcastMode;

        // Since the simulcast mode has changed, we need to update the
        // translated video channels, in particular the SSRC rewriting engine
        // needs to be updated/configured to actually perform SSRC rewriting of
        // the rewritten streams.

        this.updateTranslatedVideoChannels();

        firePropertyChange(
            SIMULCAST_MODE_PNAME, oldSimulcastMode, newSimulcastMode);
    }

    /**
     * Gets the <tt>SimulcastMode</tt> of this <tt>VideoChannel</tt>.
     *
     * @return The <tt>SimulcastMode</tt> of this <tt>VideoChannel</tt>.
     */
    public SimulcastMode getSimulcastMode()
    {
        return simulcastMode;
    }

    /**
     * Updates the view that this <tt>VideoChanel</tt> has of all the translated
     * <tt>VideoChannel</tt>s.
     */
    public void updateTranslatedVideoChannels()
    {
        logDebug("Updating the translated channels.");
        for (Channel peerVideoChannel : getContent().getChannels())
        {
            if (!(peerVideoChannel instanceof VideoChannel))
            {
                logWarn("Er, what? I Taw a Putty Tat.");
                continue;
            }

            updateTranslatedVideoChannel((VideoChannel) peerVideoChannel);
        }
    }

    /**
     * Updates the view that this <tt>VideoChannel</tt> has of the translated
     * peer <tt>VideoChannel</tt>.
     *
     * @param peerVideoChannel
     */
    public void updateTranslatedVideoChannel(VideoChannel peerVideoChannel)
    {
        if (peerVideoChannel == null)
        {
            logWarn("Can't update our view of the peer video channel because " +
                    "the peerVideoChannel is null.");
            return;
        }

        if (peerVideoChannel == this)
        {
            logDebug("Won't update our view of the peer video channel because" +
                    " peerVideoChannel is this.");
            return;
        }

        if (simulcastMode == null)
        {
            // FIXME Instead we should do something like this.
            // setSimulcastMode(SimulcastMode.REWRITING);
            logWarn("Aborting: simulcast mode is not set, but it is required.");
            return;
        }

        // In the same spirit as MediaStreamImpl.update() but for signaling.
        if (simulcastMode != SimulcastMode.REWRITING)
        {
            logDebug("Simulcast mode is not rewriting.");
        }

        // Update the SSRC rewriting engine from the peer simulcast engine
        // state.
        SimulcastEngine sim
            = peerVideoChannel.getTransformEngine().getSimulcastEngine();

        if (sim == null)
        {
            logDebug("Can't update our view of the peer video channel because" +
                    " peerSimulcastEngine is null.");
            return;
        }

        SortedSet<SimulcastLayer> layers
            = sim.getSimulcastReceiver().getSimulcastLayers();

        if (layers == null || layers.size() == 0)
        {
            logDebug("Can't update our view of the peer video channel because" +
                    " the peer doesn't have any simulcast layers.");
            return;
        }

        logDebug("Updating our view of the peer video channel.");
        final Set<Integer> ssrcGroup = new HashSet<Integer>();
        final Map<Integer, Integer> rtxGroups = new HashMap<Integer, Integer>();

        for (SimulcastLayer layer : layers)
        {
            int primarySSRC = (int) layer.getPrimarySSRC();
            int rtxSSRC = (int) layer.getRTXSSRC();

            ssrcGroup.add(primarySSRC);

            if (rtxSSRC != -1)
            {
                rtxGroups.put(rtxSSRC, primarySSRC);
            }
        }

        SimulcastLayer baseLayer = layers.first();
        final Integer ssrcTargetPrimary = (int) baseLayer.getPrimarySSRC();
        final Integer ssrcTargetRTX = (int) baseLayer.getRTXSSRC();

        // Update the SSRC rewriting engine from the media stream state.
        final Map<Integer, Byte> ssrc2fec = new HashMap<Integer, Byte>();
        final Map<Integer, Byte> ssrc2red = new HashMap<Integer, Byte>();

        for (Map.Entry<Byte, MediaFormat> entry :
            peerVideoChannel.getStream().getDynamicRTPPayloadTypes().entrySet())
        {
            Byte pt = entry.getKey();
            MediaFormat format = entry.getValue();
            if (Constants.RED.equals(format.getEncoding()))
            {
                for (Integer ssrc : ssrcGroup)
                {
                    ssrc2red.put(ssrc, pt);
                }

                for (Integer ssrc : rtxGroups.keySet())
                {
                    ssrc2red.put(ssrc, pt);
                }
            }

            if (Constants.ULPFEC.equals(entry.getValue().getEncoding()))
            {
                for (Integer ssrc : ssrcGroup)
                {
                    ssrc2fec.put(ssrc, pt);
                }

                for (Integer ssrc : rtxGroups.keySet())
                {
                    ssrc2fec.put(ssrc, pt);
                }
            }
        }

        getStream().configureSSRCRewriting(ssrcGroup, ssrcTargetPrimary,
            ssrc2fec, ssrc2red, rtxGroups, ssrcTargetRTX);
    }

    private void logDebug(String msg)
    {
        if (logger.isDebugEnabled())
        {
            msg = getEndpoint().getID() + ": " + msg;
            logger.debug(msg);
        }
    }

    private void logWarn(String msg)
    {
        if (logger.isWarnEnabled())
        {
            msg = getEndpoint().getID() + ": " + msg;
            logger.warn(msg);
        }
    }

}
