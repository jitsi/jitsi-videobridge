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
import java.util.concurrent.atomic.*;

import javax.media.rtp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.ice4j.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger; // Disambiguation.
import org.jitsi.util.concurrent.*;
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
     * The name of the property used to disable the logic which detects and
     * marks/discards packets coming from "unused" streams.
     */
    public static final String DISABLE_LASTN_UNUSED_STREAM_DETECTION
        = "org.jitsi.videobridge.DISABLE_LASTN_UNUSED_STREAM_DETECTION";

    /**
     * The name of the property which controls whether {@link VideoChannel}s
     * periodically log statistics related to oversending data.
     */
    public static final String LOG_OVERSENDING_STATS_PNAME
        = "org.jitsi.videobridge.LOG_OVERSENDING_STATS";

    /**
     * The {@link Logger} used by the {@link VideoChannel} class to print debug
     * information. Note that instances should use {@link #logger} instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(VideoChannel.class);

    /**
     * The {@link Timer} used to execute sending of delayed FIR requests for all
     * {@link VideoChannel}s.
     */
    private static final Timer delayedFirTimer = new Timer();

    /**
     * The {@link RecurringRunnableExecutor} instance for {@link VideoChannel}s.
     */
    private static RecurringRunnableExecutor recurringExecutor;

    /**
     * Configuration property for number of streams to cache
     */
    public final static String ENABLE_LIPSYNC_HACK_PNAME
        = VideoChannel.class.getName() + ".ENABLE_LIPSYNC_HACK";

    /**
     * The object that implements a hack for LS for this {@link Endpoint}.
     */
    private final LipSyncHack lipSyncHack;

    /**
     * @return the {@link RecurringRunnableExecutor} instance for
     * {@link VideoChannel}s. Uses lazy initialization.
     */
    private static synchronized RecurringRunnableExecutor getRecurringExecutor()
    {
        if (recurringExecutor == null)
        {
            recurringExecutor
                = new RecurringRunnableExecutor(
                VideoChannel.class.getSimpleName());
        }

        return recurringExecutor;
    }

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
                        classLogger.error(t);
                }
            }
        }
    }

    /**
     * XXX Defaulting to the lowest-quality simulcast stream until we are
     * explicitly told to switch to a higher-quality simulcast stream is one way
     * to go, of course. But such a default presents the problem that a remote
     * peer will see the lowest quality possible for a noticeably long period of
     * time because its command to switch to the highest quality possible can
     * only come via its data/SCTP channel and that may take a very (and
     * unpredictably) long time to set up. That is why we may default to the
     * highest-quality simulcast stream here.
     *
     * This value can be set through colibri channel IQ with
     * receive-simulcast-layer attribute.
     *
     * XXX(boris) I cannot find the semantics of this field documented anywhere.
     * It is used inconsistently (only when a SimulcastSender is created, but
     * not when the targetOrder changes for another reason), and the original
     * intention seems lost.
     */
    private int receiveSimulcastLayer
            = SimulcastStream.SIMULCAST_LAYER_ORDER_BASE; // Integer.MAX_VALUE;

    /**
     * The <tt>SimulcastMode</tt> for this <tt>VideoChannel</tt>.
     */
    private SimulcastMode simulcastMode;

    /**
     * The instance which controls which endpoints' video streams are to be
     * forwarded on this {@link VideoChannel} (i.e. implements last-n and its
     * extensions (pinned endpoints, adaptation).
     */
    private final LastNController lastNController = new LastNController(this);

    /**
     * The instance which will be computing the incoming bitrate for this
     * <tt>VideoChannel</tt>.
     * @deprecated We should use the statistics from the media stream for this.
     */
    private final RateStatistics incomingBitrate
        = new RateStatistics(INCOMING_BITRATE_INTERVAL_MS, 8000F);

    /**
     * The indicator which determines whether this <tt>VideoChannel</tt> is in
     * any <tt>VideoChannel</tt>/<tt>Endpoint</tt>'s <tt>lastN</tt>.
     */
    private final AtomicBoolean inLastN = new AtomicBoolean(true);

    /**
     * Whether the bridge should request retransmissions for missing packets
     * on this channel.
     */
    private final boolean requestRetransmissions;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The task which is to send a FIR on this channel, after a delay.
     */
    private TimerTask delayedFirTask;

    /**
     * The object used to synchronize access to {@link #delayedFirTask}.
     */
    private final Object delayedFirTaskSyncRoot = new Object();

    /**
     * A {@link RecurringRunnable} which runs periodically and logs statistics
     * related to oversending for this {@link VideoChannel}.
     */
    private final RecurringRunnable logOversendingStatsRunnable;

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

        logger
            = Logger.getLogger(
                    classLogger,
                    content.getConference().getLogger());

        ConfigurationService cfg
            = content.getConference().getVideobridge()
            .getConfigurationService();

        this.lipSyncHack
            = cfg != null && cfg.getBoolean(ENABLE_LIPSYNC_HACK_PNAME, true)
            ? new LipSyncHack(this) : null;

        initializeTransformerEngine();

        requestRetransmissions
            = cfg != null
                && cfg.getBoolean(
                        VideoMediaStream.REQUEST_RETRANSMISSIONS_PNAME, false);

        if (cfg != null && cfg.getBoolean(LOG_OVERSENDING_STATS_PNAME, false))
        {
            logOversendingStatsRunnable = createLogOversendingStatsRunnable();
            getRecurringExecutor().registerRecurringRunnable(
                logOversendingStatsRunnable);
        }
        else
        {
            logOversendingStatsRunnable = null;
        }
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
        initialize(null);
    }

    @Override
    void initialize(RTPLevelRelayType rtpLevelRelayType)
        throws IOException
    {
        super.initialize(rtpLevelRelayType);

        ConfigurationService cfg
            = getContent().getConference().getVideobridge()
                .getConfigurationService();

        if (cfg == null)
        {
            logger.warn("NOT initializing RTCP n' NACK termination because "
                    + "the configuration service was not found.");
            return;
        }

        // Initialize the RTCP termination strategy from the configuration.
        String strategyFQN = cfg.getString(RTCP_TERMINATION_STRATEGY_PNAME, "");
        if (!StringUtils.isNullOrEmpty(strategyFQN))
        {

            RTCPTerminationStrategy strategy = null;
            try
            {
                strategy = (RTCPTerminationStrategy)
                    Class.forName(strategyFQN).newInstance();
            }
            catch (Exception e)
            {
                logger.error(
                        "Failed to configure the video channel RTCP termination"
                        + " strategy.",
                        e);
            }

            if (strategy != null)
            {
                logger.debug("Initializing RTCP termination.");
                getStream().setRTCPTerminationStrategy(strategy);
            }

        }

        boolean enableNackTermination
                = !cfg.getBoolean(DISABLE_NACK_TERMINATION_PNAME, false);
        if (enableNackTermination)
        {
            logger.debug("Initializing NACK termination.");
            MediaStream stream = getStream();
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
     * Gets the object that implements a hack for LS for this
     * {@link VideoChannel}.
     *
     * @return the object that implements a hack for LS for this
     * {@link VideoChannel}.
     */
    public LipSyncHack getLipSyncHack()
    {
        return lipSyncHack;
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
        boolean addLastNTransformEngine = true;

        ConfigurationService cfg
            = getContent().getConference().getVideobridge()
                    .getConfigurationService();
        if (cfg != null)
        {
            addLastNTransformEngine
                = !cfg.getBoolean(DISABLE_LASTN_UNUSED_STREAM_DETECTION, false);
        }

        if (addLastNTransformEngine && (engines != null) && (engines.length != 0))
        {
            for (TransformEngine engine : engines)
            {
                if (engine instanceof LastNTransformEngine)
                {
                    addLastNTransformEngine = false;
                    break;
                }
            }
        }
        if (addLastNTransformEngine)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Adding LastNTransformEngine " + getLoggingId());
            }
            chain.addEngine(new LastNTransformEngine(this));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;

        super.describe(iq);

        iq.setLastN(lastNController.getLastN());
        iq.setSimulcastMode(getSimulcastMode());
    }

    /**
     * Returns the current incoming bitrate in bits per second for this
     * <tt>VideoChannel</tt> (computed as the average bitrate over the last
     * {@link #INCOMING_BITRATE_INTERVAL_MS} milliseconds).
     *
     * @deprecated We should use the statistics from the media stream for this.
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
        return lastNController.getLastN();
    }

    public int getReceiveSimulcastLayer()
    {
        return receiveSimulcastLayer;
    }

    public void setReceiveSimulcastLayer(Integer receiveSimulcastLayer)
    {
        if (receiveSimulcastLayer != null)
        {
            this.receiveSimulcastLayer = receiveSimulcastLayer;
        }
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
                logger.error(
                        "Failed to send \"in last-N\" update to: "
                            + endpoint.getID(), ex);
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
     * Determines whether a specific <tt>Channel</tt> is within the set of
     * <tt>Channel</tt>s limited by <tt>lastN</tt> i.e. whether the RTP video
     * streams of the specified channel are to be sent to the remote endpoint of
     * this <tt>Channel</tt>.
     *
     * @param channel the <tt>Channel</tt> to be checked whether it is within
     * the set of <tt>Channel</tt>s limited by <tt>lastN</tt> i.e. whether its
     * RTP streams are to be sent to the remote endpoint of this
     * <tt>Channel</tt>
     * @return <tt>true</tt> if the RTP streams of <tt>channel</tt> are to be
     * sent to the remote endpoint of this <tt>Channel</tt>; otherwise,
     * <tt>false</tt>. The implementation of the <tt>RtpChannel</tt> class
     * always returns <tt>true</tt>.
     */
    public boolean isInLastN(RtpChannel channel)
    {
        return lastNController.isForwarded(channel);
    }

    @Override
    public void propertyChange(PropertyChangeEvent ev)
    {
        super.propertyChange(ev);

        String propertyName = ev.getPropertyName();

        if (Endpoint.PINNED_ENDPOINTS_PROPERTY_NAME.equals(propertyName))
        {
            lastNController.setPinnedEndpointIds((Set<String>)ev.getNewValue());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean rtpTranslatorWillWrite(
        boolean data,
        byte[] buffer, int offset, int length,
        RtpChannel source)
    {
        boolean accept = lastNController.isForwarded(source);

        if (lipSyncHack != null)
        {
            lipSyncHack.onRTPTranslatorWillWriteVideo(
                accept, data, buffer, offset, length, this);
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
        {
            if (lastNController.getLastN() >= 0 ||
                    lastNController.getCurrentLastN() >= 0)
            {
                lastNController.initializeConferenceEndpoints();
                sendLastNEndpointsChangeEventOnDataChannel(
                        lastNController.getForwardedEndpoints(),
                        null,
                        null);
            }

            updateInLastN(this);
        }
    }

    /**
     * {@inheritDoc}
     * Closes the {@link LastNController} before expiring the channel.
     */
    @Override
    public boolean expire()
    {
        if (!super.expire())
        {
            // Already expired.
            return false;
        }

        if (lastNController != null)
        {
            lastNController.close();
        }

        synchronized (delayedFirTaskSyncRoot)
        {
            if (delayedFirTask != null)
            {
                delayedFirTask.cancel();
                delayedFirTask = null;
            }
        }

        if (recurringExecutor != null && logOversendingStatsRunnable != null)
        {
            recurringExecutor.
                deRegisterRecurringRunnable(logOversendingStatsRunnable);
        }
        return true;
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
    public void sendLastNEndpointsChangeEventOnDataChannel(
            List<String> forwardedEndpoints,
            List<String> endpointsEnteringLastN,
            List<String> conferenceEndpoints)
    {
        Endpoint thisEndpoint = getEndpoint();

        if (thisEndpoint == null)
            return;

        // We want endpointsEnteringLastN to always to reported. Consequently,
        // we will pretend that all lastNEndpoints are entering if no explicit
        // endpointsEnteringLastN is specified.
        // XXX do we really want that?
        if (endpointsEnteringLastN == null)
            endpointsEnteringLastN = forwardedEndpoints;

        // XXX Should we just build JSON here?
        // colibriClass
        StringBuilder msg
            = new StringBuilder(
                    "{\"colibriClass\":\"LastNEndpointsChangeEvent\"");

        {
            // lastNEndpoints
            msg.append(",\"lastNEndpoints\":");
            msg.append(getJsonString(forwardedEndpoints));

            // endpointsEnteringLastN
            msg.append(",\"endpointsEnteringLastN\":");
            msg.append(getJsonString(endpointsEnteringLastN));

            // conferenceEndpoints
            msg.append(",\"conferenceEndpoints\":");
            msg.append(getJsonString(conferenceEndpoints));
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

    private String getJsonString(List<String> strings)
    {
        JSONArray array = new JSONArray();
        if (strings != null && !strings.isEmpty())
        {
            for (String s : strings)
            {
                array.add(s);
            }
        }
        return array.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastN(Integer lastN)
    {
        lastNController.setLastN(lastN);

        touch(); // It seems this Channel is still active.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    List<Endpoint> speechActivityEndpointsChanged(List<Endpoint> endpoints)
    {
        return lastNController.speechActivityEndpointsChanged(endpoints);
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

        setInLastN(inLastN);
    }

    /**
     * Sets the value of the {@code inLastN} property to {@code newValue}.
     * @param newValue the value to set.
     */
    void setInLastN(boolean newValue)
    {
        if (this.inLastN.compareAndSet(!newValue, newValue))
        {
            inLastNChanged(!newValue, newValue);
        }
    }

    /**
     * Updates the {@code inLastN} property of this {@link VideoChannel}.
     */
    void updateInLastN()
    {
        Channel[] channels = getContent().getChannels();
        updateInLastN(channels);
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

        // TODO remove this whole method.
        boolean enableRedFilter = true;

        // If we're not given any PTs at all, assume that we shouldn't touch
        // RED.
        if (payloadTypes == null || payloadTypes.isEmpty())
            return;

        for (PayloadTypePacketExtension payloadType : payloadTypes)
        {
            if (Constants.RED.equals(payloadType.getName()))
            {
                enableRedFilter = false;
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
        long ssrc = nackPacket.sourceSSRC;
        Set<Integer> lostPackets = new TreeSet<>(nackPacket.getLostPackets());

        if (logger.isDebugEnabled())
        {
            logger.debug(Logger.Category.STATISTICS,
                         "nack_received," + getLoggingId()
                         + " ssrc=" + ssrc
                         + ",lost_packets=" + lostPackets);
        }

        RawPacketCache cache;
        RtxTransformer rtxTransformer;
        MediaStream stream = getStream();

        if (stream != null && (cache = stream.getPacketCache()) != null
                && (rtxTransformer = transformEngine.getRtxTransformer())
                        != null)
        {
            // XXX The retransmission of packets MUST take into account SSRC
            // rewriting. Which it may do by injecting retransmitted packets
            // AFTER the SsrcRewritingEngine.
            // Also, the cache MUST be notified of packets being retransmitted,
            // in order for it to update their timestamp. We do this here by
            // simply letting retransmitted packets pass through the cache again.
            // We use the retransmission requester here simply because it is
            // the transformer right before the cache, not because of anything
            // intrinsic to it.
            RetransmissionRequester rr = stream.getRetransmissionRequester();
            TransformEngine after
                = (rr instanceof TransformEngine) ? (TransformEngine) rr : null;

            long rtt = getStream().getMediaStreamStats().getSendStats().getRtt();
            long now = System.currentTimeMillis();

            for (Iterator<Integer> i = lostPackets.iterator(); i.hasNext();)
            {
                int seq = i.next();
                RawPacketCache.Container container
                    = cache.getContainer(ssrc, seq);


                if (container != null)
                {
                    // Cache hit.
                    long delay = now - container.timeAdded;
                    boolean send = (rtt == -1) ||
                        (delay >= Math.min(rtt * 0.9, rtt - 5));

                    if (logger.isDebugEnabled())
                    {
                        logger.debug(Logger.Category.STATISTICS,
                                     "retransmitting," + getLoggingId()
                                     + " ssrc=" + ssrc
                                     + ",seq=" + seq
                                     + ",send=" + send);
                    }

                    if (send && rtxTransformer.retransmit(container.pkt, after))
                    {
                        statistics.packetsRetransmitted.incrementAndGet();
                        statistics.bytesRetransmitted.addAndGet(
                            container.pkt.getLength());
                        i.remove();
                    }

                    if (!send)
                    {
                        statistics.packetsNotRetransmitted.incrementAndGet();
                        statistics.bytesNotRetransmitted.addAndGet(
                            container.pkt.getLength());
                        i.remove();
                    }

                }
                else
                {
                    statistics.packetsMissingFromCache.incrementAndGet();
                }
            }
        }

        if (!lostPackets.isEmpty())
        {
            if (requestRetransmissions)
            {
                // If retransmission requests are enabled, videobridge assumes
                // the responsibility of requesting missing packets.
                logger.debug("Packets missing from the cache. Ignoring, because"
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
            logger.warn("Failed to create NACK packet: " + ioe);
        }

        if (pkt != null)
        {
            Set<RtpChannel> channelsToSendTo = new HashSet<>();
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
                    logger.debug(Logger.Category.STATISTICS,
                                 "sending_nack," + getLoggingId()
                                 + " ssrc=" + mediaSourceSsrc
                                 + ",seqs=" + seqs);
                }

                try
                {
                    c.getStream().injectPacket(
                            pkt,
                            /* data */ false,
                            /* after */ null);
                }
                catch (TransmissionFailedException e)
                {
                    logger.warn("Failed to inject packet in MediaStream: " + e);
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
        if (sourceGroups == null || sourceGroups.isEmpty())
        {
            return;
        }

        // Setup simulcast streams from source groups.
        SimulcastEngine simulcastEngine
            = getTransformEngine().getSimulcastEngine();

        // Build the simulcast streams.
        long[][] simulcastTriplets = null;
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
            simulcastTriplets = new long[sources.size()][];
            for (int i = 0; i < sources.size(); i++)
            {
                SourcePacketExtension source = sources.get(i);
                // FIXME we need an INVALID_SSRC constant.
                simulcastTriplets[i] = new long[] { source.getSSRC(), -1, -1 };
            }
        }

        if (simulcastTriplets == null || simulcastTriplets.length == 0)
        {
            return;
        }

        SimulcastStream[] simulcastStreams
            = new SimulcastStream[simulcastTriplets.length];

        for (int i = 0; i < simulcastTriplets.length; i++)
        {
            simulcastStreams[i] = new SimulcastStream(
                simulcastEngine.getSimulcastReceiver(),
                simulcastTriplets[i][0],
                simulcastTriplets[i][1],
                simulcastTriplets[i][2],
                i);
        }

        simulcastEngine
            .getSimulcastReceiver().setSimulcastStreams(simulcastStreams);
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
            logger.debug("Setting simulcast mode to " + newSimulcastMode);
        }

        simulcastMode = newSimulcastMode;

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
     * {@inheritDoc}
     */
    @Override
    public void setAdaptiveLastN(boolean adaptiveLastN)
    {
        lastNController.setAdaptiveLastN(adaptiveLastN);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAdaptiveSimulcast(boolean adaptiveSimulcast)
    {
        lastNController.setAdaptiveSimulcast(adaptiveSimulcast);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dominantSpeakerChanged()
    {
        Endpoint dominantEndpoint = conferenceSpeechActivity.getDominantEndpoint();

        if (getEndpoint().equals(dominantEndpoint))
        {
            // We are the new dominant speaker. We expect other endpoints to
            // mark us as a selected endpoint as soon as they receive the
            // notification.

            if (getContent().getChannelCount() < 3)
            {
                // If there is only one other endpoint in the conference, it
                // already has us selected.
                return;
            }

            long senderRtt = getRtt();
            long maxReceiverRtt = getMaxReceiverDelay();

            if (maxReceiverRtt > 0 && senderRtt > 0)
            {
                // We add an additional 10ms delay to reduce the risk of the kf
                // arriving too early.
                long firDelay = maxReceiverRtt - senderRtt + 10;
                if (logger.isInfoEnabled())
                {
                    logger.info(Logger.Category.STATISTICS,
                                "schedule_fir," + getLoggingId()
                                + " delay=" + firDelay);
                }
                scheduleFir(firDelay);
            }
        }
        else
        {
            synchronized (delayedFirTaskSyncRoot)
            {
                if (delayedFirTask != null)
                {
                    delayedFirTask.cancel();
                }
            }
        }
    }

    /**
     * @return the RTT in milliseconds.
     */
    private long getRtt()
    {
        long rtt = -1;
        MediaStream stream = getStream();
        if (stream != null)
        {
            rtt = stream.getMediaStreamStats().getReceiveStats().getRtt();
        }
        return rtt;
    }

    /**
     * @return the maximum round trip time in milliseconds from other video
     * channels in this channel's content.
     */
    private long getMaxReceiverDelay()
    {
        long maxRtt = -1;
        for (Channel channel : getContent().getChannels())
        {
            if (channel instanceof VideoChannel && !this.equals(channel))
            {
                long rtt = ((VideoChannel) channel).getRtt();
                if (maxRtt < rtt)
                    maxRtt = rtt;
            }
        }

        return maxRtt;
    }

    /**
     * Schedules a FIR to be sent to the remote side for the SSRC of the high
     * quality simulcast stream, after a delay given in milliseconds.
     * @param delay the delay in milliseconds before the FIR is to be sent.
     */
    private void scheduleFir(final long delay)
    {
        TimerTask task = new TimerTask()
        {
            @Override
            public void run()
            {
                if (isExpired())
                    return;

                SimulcastReceiver simulcastReceiver
                    = getTransformEngine().getSimulcastEngine()
                            .getSimulcastReceiver();
                SimulcastStream[] streams
                    = simulcastReceiver.getSimulcastStreams();
                if (streams != null && streams.length > 0)
                {
                    // The ssrc for the HQ layer.
                    int ssrc
                        = (int) streams[streams.length - 1].getPrimarySSRC();
                    askForKeyframes(new int[]{ ssrc });
                }
            }
        };

        synchronized (delayedFirTaskSyncRoot)
        {
            if (delayedFirTask != null)
            {
                logger.warn("Canceling an existing delayed FIR task for "
                                + "endpoint " + getEndpoint().getID() + ".");
                delayedFirTask.cancel();
            }
            delayedFirTask = task;
        }

        delayedFirTimer.schedule(task, Math.max(0, delay));
    }

    /**
     * Creates a {@link PeriodicRunnable} which logs statistics related to
     * oversending for this {@link VideoChannel}.
     *
     * @return the created instance.
     */
    private RecurringRunnable createLogOversendingStatsRunnable()
    {
        return new PeriodicRunnable(1000)
        {
            private BandwidthEstimator bandwidthEstimator = null;

            @Override
            public void run()
            {
                super.run();

                if (bandwidthEstimator == null)
                {
                    VideoMediaStream videoStream
                        = (VideoMediaStream) getStream();
                    if (videoStream != null)
                    {
                        bandwidthEstimator
                            = videoStream.getOrCreateBandwidthEstimator();
                    }
                }

                if (bandwidthEstimator == null)
                {
                    return;
                }

                long bwe = bandwidthEstimator.getLatestEstimate();
                if (bwe <= 0)
                {
                    return;
                }

                long sendingBitrate = 0;
                for (RtpChannel channel : getEndpoint().getChannels(null))
                {
                    sendingBitrate +=
                        channel
                            .getStream()
                            .getMediaStreamStats()
                            .getSendStats().getBitrate();
                }

                if (sendingBitrate <= 0)
                {
                    return;
                }

                double lossRate
                    = getStream()
                        .getMediaStreamStats().getSendStats().getLossRate();

                logger.info(Logger.Category.STATISTICS,
                           "sending_bitrate," + getLoggingId()
                           + " bwe=" + bwe
                           + ",sbr=" + sendingBitrate
                           + ",loss=" + lossRate
                           + ",remb=" + bandwidthEstimator.getLatestREMB()
                           + ",rrLoss="
                               + bandwidthEstimator.getLatestFractionLoss());
            }
        };
    }
}
