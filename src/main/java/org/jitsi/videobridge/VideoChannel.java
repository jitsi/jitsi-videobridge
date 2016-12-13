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
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.util.Logger; // Disambiguation.
import org.jitsi.videobridge.ratecontrol.*;
import org.json.simple.*;

/**
 * Implements an <tt>RtpChannel</tt> with <tt>MediaType.VIDEO</tt>.
 *
 * @author Lyubomir Marinov
 * @author George Politis
 */
public class VideoChannel
    extends RtpChannel
{
    /**
     * The length in milliseconds of the interval for which the average incoming
     * bitrate for this video channel will be computed and made available
     * through {@link #getIncomingBitrate}.
     */
    private static final int INCOMING_BITRATE_INTERVAL_MS = 5000;

    /**
     * The name of the property which specifies the simulcast mode of a
     * <tt>VideoChannel</tt>.
     */
    public static final String SIMULCAST_MODE_PNAME
        = "org.jitsi.videobridge.VideoChannel.simulcastMode";

    /**
     * The name of the property used to disable the logic which detects and
     * marks/discards packets coming from "unused" streams.
     */
    public static final String DISABLE_LASTN_UNUSED_STREAM_DETECTION
        = "org.jitsi.videobridge.DISABLE_LASTN_UNUSED_STREAM_DETECTION";

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
     * The <tt>SimulcastMode</tt> for this <tt>VideoChannel</tt>.
     */
    private SimulcastMode simulcastMode;

    /**
     * The instance which controls which endpoints' video streams are to be
     * forwarded on this {@link VideoChannel} (i.e. implements last-n and its
     * extensions (pinned endpoints, adaptation).
     */
    private final AdaptiveBitrateController bitrateController
        = new AdaptiveBitrateController(this);

    /**
     * The instance that is aware of all of the RTP encodings of the remote
     * endpoint.
     */
    private final MediaStreamTrackReceiver mediaStreamTrackReceiver
        = new MediaStreamTrackReceiver(this);

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

        initializeTransformerEngine();
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
     * {@inheritDoc}
     */
    @Override
    public MediaStreamTrackReceiver getMediaStreamTrackReceiver()
    {
        return mediaStreamTrackReceiver;
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
                logger.debug("Adding LastNTransformEngine for endpoint "
                                 + getChannelBundleId());
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

        iq.setLastN(bitrateController.getLastN());
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
        return bitrateController.getLastN();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BitrateController getBitrateController()
    {
        return bitrateController;
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
    public boolean isInLastN(Channel channel)
    {
        return bitrateController.isForwarded(channel);
    }

    @Override
    public void propertyChange(PropertyChangeEvent ev)
    {
        super.propertyChange(ev);

        String propertyName = ev.getPropertyName();

        if (Endpoint.PINNED_ENDPOINTS_PROPERTY_NAME.equals(propertyName))
        {
            bitrateController
                .setPinnedEndpointIds((List<String>)ev.getNewValue());
        }
        else if (
            Endpoint.SELECTED_ENDPOINTS_PROPERTY_NAME.equals(propertyName))
        {
            bitrateController
                .setSelectedEndpointIds((List<String>)ev.getNewValue());
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
        boolean accept = bitrateController
            .rtpTranslatorWillWrite(data, buffer, offset, length, source);

        LipSyncHack lipSyncHack = getEndpoint().getLipSyncHack();

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
            if (bitrateController.getLastN() >= 0)
            {
                bitrateController.initializeConferenceEndpoints();
                sendLastNEndpointsChangeEventOnDataChannel(
                        bitrateController.getForwardedEndpoints(),
                        null,
                        null);
            }

            updateInLastN(this);
        }
    }

    /**
     * {@inheritDoc}
     * Closes the {@link AdaptiveBitrateController} before expiring the channel.
     */
    @Override
    public boolean expire()
    {
        if (!super.expire())
        {
            // Already expired.
            return false;
        }

        try
        {
            bitrateController.close();
        }
        catch (Exception e)
        {
            logger.error(e);
        }

        synchronized (delayedFirTaskSyncRoot)
        {
            if (delayedFirTask != null)
            {
                delayedFirTask.cancel();
                delayedFirTask = null;
            }
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
        bitrateController.setLastN(lastN);

        touch(); // It seems this Channel is still active.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    List<Endpoint> speechActivityEndpointsChanged(List<Endpoint> endpoints)
    {
        return bitrateController.speechActivityEndpointsChanged(endpoints);
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
    public void setInLastN(boolean newValue)
    {
        if (this.inLastN.compareAndSet(!newValue, newValue))
        {
            inLastNChanged(!newValue, newValue);
        }
    }

    /**
     * Updates the {@code inLastN} property of this {@link VideoChannel}.
     */
    public void updateInLastN()
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
     * {@inheritDoc}
     */
    @Override
    public boolean setRtpEncodingParameters(
        List<SourcePacketExtension> sources,
        List<SourceGroupPacketExtension> sourceGroups)
    {
        boolean changed = super.setRtpEncodingParameters(sources, sourceGroups);

        if (changed)
        {
            for (Channel channel : getContent().getChannels())
            {
                if (channel != this)
                {
                    BitrateController dstBitrateController
                        = ((RtpChannel) channel).getBitrateController();

                    if (dstBitrateController != null)
                    {
                        dstBitrateController.rtpEncodingParametersChanged(this);
                    }
                }
            }
        }

        return changed;
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
                    logger.info("Scheduling a keyframe request for endpoint "
                                    + getEndpoint().getID() + " with a delay of "
                                    + firDelay + "ms.");
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

                RTPEncodingImpl defaultEncoding
                    = mediaStreamTrackReceiver.getDefaultEncoding();

                if (defaultEncoding == null)
                {
                    logger.warn("Unable to schedule FIR" +
                        ",stream_hash=" + getStream().hashCode());

                    return;
                }

                askForKeyframes((int) defaultEncoding.getPrimarySSRC());
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
}
