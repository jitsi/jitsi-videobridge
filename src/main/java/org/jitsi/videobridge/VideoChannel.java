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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.ice4j.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger; // Disambiguation.
import org.jitsi.util.concurrent.*;
import org.jitsi.videobridge.cc.*;
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
     * The name of the property used to disable LastN notifications.
     */
    public static final String DISABLE_LASTN_NOTIFICATIONS_PNAME
        = "org.jitsi.videobridge.DISABLE_LASTN_NOTIFICATIONS";

    /**
     * The Java system property name that holds the boolean that indicates
     * whether or not to route the default video SSRCs that Chrome picks to send
     * RTCP feedback with (when there's no SSRC signaled in the local
     * description) to the video channel.
     */
    public static final String DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS_PNAME
        = "org.jitsi.videobridge.DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS";

    /**
     * The name of the property used to disable NACK termination.
     */
    @Deprecated
    public static final String DISABLE_NACK_TERMINATION_PNAME
        = "org.jitsi.videobridge.DISABLE_NACK_TERMINATION";

    /**
     * Configuration property for number of streams to cache
     */
    final static String ENABLE_LIPSYNC_HACK_PNAME
        = VideoChannel.class.getName() + ".ENABLE_LIPSYNC_HACK";

    /**
     * The name of the property which controls whether {@link VideoChannel}s
     * periodically log statistics related to oversending data.
     */
    private static final String LOG_OVERSENDING_STATS_PNAME
        = "org.jitsi.videobridge.LOG_OVERSENDING_STATS";

    /**
     * The ConfigurationService to get config values from.
     */
    private static final ConfigurationService cfg
        = LibJitsi.getConfigurationService();

    /**
     * A boolean that indicates whether or not to route the default video SSRCs
     * that Chrome picks to send RTCP feedback with (when there's no SSRC
     * signaled in the local description) to the video channel.
     */
    public static final boolean DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS
        = cfg.getBoolean(DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS_PNAME, false);

    /**
     * The default SSRC that Chrome picks to send RTCP feedback with, when
     * there's no SSRC signaled in the local description.
     */
    private static final int[] DEFAULT_RTCP_RECV_REPORT_SSRCS
        = DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS
            ? new int[0] : new int[] { 1, 2 };

    /**
     * The default maximum frame height (in pixels) that can be forwarded to
     * this participant
     */
    private static final int MAX_FRAME_HEIGHT_DEFAULT = 2160;

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
     * A boolean that indicates whether or not we should send data channel
     * notifications to the endpoint about changes in the endpoints that it
     * receives.
     */
    private final boolean disableLastNNotifications;

    /**
     * The object that implements a hack for LS for this {@link Endpoint}.
     */
    private final LipSyncHack lipSyncHack;

    /**
     * Maximum frame height, in pixels, for any video stream forwarded to this receiver
     */
    private int maxFrameHeight = MAX_FRAME_HEIGHT_DEFAULT;

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
     * The instance which controls which endpoints' video streams are to be
     * forwarded on this {@link VideoChannel} (i.e. implements last-n and its
     * extensions (pinned endpoints, adaptation).
     */
    private final BitrateController bitrateController
        = new BitrateController(this);

    /**
     * The instance which probes for bandwidth on this {@link VideoChannel}.
     */
    private final BandwidthProbing bandwidthProbing
        = new BandwidthProbing(this);

    /**
     * The instance which will be computing the incoming bitrate for this
     * <tt>VideoChannel</tt>.
     * @deprecated We should use the statistics from the media stream for this.
     */
    private final RateStatistics incomingBitrate
        = new RateStatistics(INCOMING_BITRATE_INTERVAL_MS, 8000F);

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
     * The maximum number of endpoints whose video streams will be forwarded
     * to the endpoint, as externally configured (by the client, by the focus
     * agent, or by default configuration). A value of {@code -1} means that
     * there is no limit, and all endpoints' video streams will be forwarded.
     */
    private int lastN = -1;

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
    VideoChannel(Content content,
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

        this.lipSyncHack
            = cfg != null && cfg.getBoolean(ENABLE_LIPSYNC_HACK_PNAME, true)
            ? new LipSyncHack(this) : null;

        disableLastNNotifications = cfg != null
            && cfg.getBoolean(DISABLE_LASTN_NOTIFICATIONS_PNAME, false);

        initializeTransformerEngine();

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

        getRecurringExecutor().registerRecurringRunnable(bandwidthProbing);
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
            Channel[] peerChannels = getContent().getChannels();
            if (!ArrayUtils.isNullOrEmpty(peerChannels))
            {
                for (Channel peerChannel : peerChannels)
                {
                    if (peerChannel == this
                        || !(peerChannel instanceof VideoChannel))
                    {
                        continue;
                    }

                    ((VideoChannel) peerChannel)
                        .bitrateController.update(null, -1);
                }
            }
        }

        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int[] getDefaultReceiveSSRCs()
    {
        return DEFAULT_RTCP_RECV_REPORT_SSRCS;
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

        bitrateController.update(null, -1);

        ((VideoMediaStream) getStream()).getOrCreateBandwidthEstimator()
            .addListener(new BandwidthEstimator.Listener()
            {
                @Override
                public void bandwidthEstimationChanged(long newValueBps)
                {
                    bitrateController.update(null, newValueBps);
                }
            });
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
     * Gets the {@link BitrateController} which controls which endpoints'
     * video streams are to be forwarded on this {@link VideoChannel} (i.e.
     * implements last-n and its extensions (pinned endpoints, adaptation).
     */
    public BitrateController getBitrateController()
    {
        return bitrateController;
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
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;

        super.describe(iq);

        iq.setLastN(lastN);
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
        return lastN;
    }

    @Override
    public void propertyChange(PropertyChangeEvent ev)
    {
        super.propertyChange(ev);

        String propertyName = ev.getPropertyName();

        if (Endpoint.PINNED_ENDPOINTS_PROPERTY_NAME.equals(propertyName)
            || Endpoint.SELECTED_ENDPOINTS_PROPERTY_NAME.equals(propertyName)
            || Conference.ENDPOINTS_PROPERTY_NAME.equals(propertyName))
        {
            bitrateController.update(null, -1);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean rtpTranslatorWillWrite(
        boolean data,
        RawPacket pkt,
        RtpChannel source)
    {
        if (!data)
        {
            return true;
        }

        boolean accept = bitrateController.accept(pkt);

        if (accept && lipSyncHack != null)
        {
            lipSyncHack
                .onRTPTranslatorWillWriteVideo(pkt, source);
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
            sendLastNEndpointsChangeEvent(
                bitrateController.getForwardedEndpoints(),
                null,
                null);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean expire()
    {
        if (!super.expire())
        {
            // Already expired.
            return false;
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

        if (recurringExecutor != null)
        {
            recurringExecutor
                .deRegisterRecurringRunnable(bandwidthProbing);
        }

        return true;
    }

    /**
     * Sends a message with <tt>colibriClass</tt>
     * <tt>LastNEndpointsChangeEvent</tt> to the <tt>Endpoint</tt> of this
     * <tt>VideoChannel</tt> in order to notify it that the list/set of
     * <tt>lastN</tt> has changed.
     *
     * @param forwardedEndpoints the collection of forwarded endpoints.
     * @param endpointsEnteringLastN the <tt>Endpoint</tt>s which are entering
     * the list of <tt>Endpoint</tt>s defined by <tt>lastN</tt>
     * @param conferenceEndpoints the collection of all endpoints in the
     * conference.
     */
    public void sendLastNEndpointsChangeEvent(
        Collection<String> forwardedEndpoints,
        Collection<String> endpointsEnteringLastN,
        Collection<String> conferenceEndpoints)
    {
        if (disableLastNNotifications)
        {
            return;
        }

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
            thisEndpoint.sendMessage(msg.toString());
        }
        catch (IOException e)
        {
            logger.error("Failed to send message on data channel.", e);
        }
    }

    private String getJsonString(Collection<String> strings)
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
    public void setLastN(int lastN)
    {
        if (this.lastN != lastN)
        {
            this.lastN = lastN;
            bitrateController.update(null, -1);
        }

        touch(); // It seems this Channel is still active.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void speechActivityEndpointsChanged(List<Endpoint> endpoints)
    {
        bitrateController.update(endpoints, -1);
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

        // Support for FIR and PLI is declared per-payload type, but currently
        // our code which requests FIR and PLI is not payload-type aware. So
        // until this changes we will just check if any of the PTs supports
        // FIR and PLI.
        boolean supportsFir = false;
        boolean supportsPli = false;

        // If we're not given any PTs at all, assume that we shouldn't touch
        // RED.
        if (payloadTypes == null || payloadTypes.isEmpty())
        {
            return;
        }

        for (PayloadTypePacketExtension payloadType : payloadTypes)
        {
            if (Constants.RED.equals(payloadType.getName()))
            {
                enableRedFilter = false;
            }

            for (RtcpFbPacketExtension rtcpFb :
                        payloadType.getChildExtensionsOfType(
                                RtcpFbPacketExtension.class))
            {
                if ("ccm".equals(rtcpFb.getAttribute("type"))
                        && "fir".equals(rtcpFb.getAttribute("subtype")))
                {
                    supportsFir = true;
                }
                else if ("nack".equals(rtcpFb.getAttribute("type"))
                    && "pli".equals(rtcpFb.getAttribute("subtype")))
                {
                    supportsPli = true;
                }
            }
        }

        // If the endpoint supports RED we disable the filter (e.g. leave RED).
        // Otherwise, we strip it.
        if (transformEngine != null)
        {
            transformEngine.enableREDFilter(enableRedFilter);
        }

        MediaStream mediaStream = getStream();
        if (mediaStream != null)
        {
            ((VideoMediaStreamImpl) mediaStream).setSupportsFir(supportsFir);
            ((VideoMediaStreamImpl) mediaStream).setSupportsPli(supportsPli);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dominantSpeakerChanged()
    {
        Endpoint dominantEndpoint = conferenceSpeechActivity.getDominantEndpoint();

        if (dominantEndpoint != null && dominantEndpoint.equals(getEndpoint()))
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
     * Set the maximum frame height, in pixels, of video streams that can be forwarded
     * to this participant.
     *
     * @param maxFrameHeight the maximum frame height, in pixels, of video streams that can be forwarded
     * to this participant;
     */
    public void setMaxFrameHeight(int maxFrameHeight)
    {
        this.maxFrameHeight = maxFrameHeight;
        this.bitrateController.update(null, -1);
    }

    /**
     * Get the maximum frame height, in pixels, of video streams that can be forwarded
     * to this participant.
     *
     * @return the maximum frame height, in pixels, of video streams that can be forwarded
     * to this participant;
     */
    public int getMaxFrameHeight()
    {
        return this.maxFrameHeight;
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

                MediaStreamTrackReceiver receiver
                    = getStream().getMediaStreamTrackReceiver();

                if (receiver == null)
                {
                    return;
                }

                MediaStreamTrackDesc[] tracks = receiver.getMediaStreamTracks();
                if (ArrayUtils.isNullOrEmpty(tracks))
                {
                    return;
                }

                RTPEncodingDesc[] encodings = tracks[0].getRTPEncodings();
                if (ArrayUtils.isNullOrEmpty(encodings))
                {
                    return;
                }

                // The ssrc for the HQ layer.
                int ssrc
                    = (int) encodings[encodings.length - 1].getPrimarySSRC();

                RTCPFeedbackMessageSender rtcpFeedbackMessageSender
                    = ((RTPTranslatorImpl)getContent().getRTPTranslator())
                    .getRtcpFeedbackMessageSender();

                if (rtcpFeedbackMessageSender != null)
                {
                    if (logger.isTraceEnabled())
                    {
                        logger.trace("send_fir,stream="
                            + getStream().hashCode()
                            + ",reason=scheduled");
                    }
                    rtcpFeedbackMessageSender.sendFIR(ssrc);
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
                Endpoint endpoint = getEndpoint();
                if (endpoint != null)
                {
                    for (RtpChannel channel : endpoint.getChannels(null))
                    {
                        sendingBitrate +=
                            channel
                                .getStream()
                                .getMediaStreamStats()
                                .getSendStats().getBitrate();
                    }
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
