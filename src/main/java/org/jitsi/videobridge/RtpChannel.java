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
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.util.*;
import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.RTPHeader;

import org.ice4j.socket.*;
import org.jitsi.eventadmin.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.impl.neomedia.transform.zrtp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.service.neomedia.stats.*;
import org.jitsi.util.Logger;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.transform.*;
import org.jitsi.videobridge.xmpp.*;

/**
 * Represents channel in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author George Politis
 */
public class RtpChannel
    extends Channel
    implements PropertyChangeListener
{
    /**
     * The property which controls whether Jitsi Videobridge will perform
     * replacement of the timestamps in the abs-send-time RTP header extension.
     */
    private static final String DISABLE_ABS_SEND_TIME_PNAME
        = "org.jitsi.videobridge.DISABLE_ABS_SEND_TIME";

    /**
     * The {@link Logger} used by the {@link RtpChannel} class to print debug
     * information. Note that instances should use {@link #logger} instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(RtpChannel.class);

    /**
     * An empty array of received synchronization source identifiers (SSRCs).
     * Explicitly defined to reduce allocations and, consequently, the effects
     * of garbage collection.
     */
    private static final long[] NO_RECEIVE_SSRCS = new long[0];

    /**
     * The maximum number of SSRCs to accept on this channel. RTP packets arriving
     */
    private static final int MAX_RECEIVE_SSRCS = 50;

    /**
     * Gets the <tt>Channel</tt> which uses a specific <tt>MediaStream</tt>.
     *
     * @param stream a <tt>MediaStream</tt> which was initialized by a
     * <tt>Channel</tt>
     * @return the <tt>Channel</tt> which uses the specified
     * <tt>MediaStream</tt> or <tt>null</tt> if the <tt>mediaStream</tt> was not
     * initialized by a <tt>Channel</tt> or is no longer used by a
     * <tt>Channel</tt>
     */
    static RtpChannel getChannel(MediaStream stream)
    {
        return (RtpChannel) stream.getProperty(RtpChannel.class.getName());
    }

    /**
     * The speech activity of the <tt>Endpoint</tt>s in the multipoint
     * conference in which this <tt>Channel</tt> is participating.
     */
    protected final ConferenceSpeechActivity conferenceSpeechActivity;

    /**
     * Holds the {@code RtpChannelDatagramFilter} instances (if any) used by
     * this channel. The filter for RTP is at index {@code 0}, the filter for
     * RTCP at index {@code 1}.
     */
    private final RtpChannelDatagramFilter[] datagramFilters
        = new RtpChannelDatagramFilter[2];

    /**
     * The local synchronization source identifier (SSRC) to be pre-announced.
     * Currently, the value is taken into account in the case of content mixing
     * and not in the case of RTP translation.
     */
    private final long initialLocalSSRC;

    /**
     * The <tt>PropertyChangeListener</tt> which listens to
     * <tt>PropertyChangeEvent</tt>s.
     */
    private final PropertyChangeListener propertyChangeListener
        = new WeakReferencePropertyChangeListener(this);

    /**
     * Contains the payload type numbers configured for this channel.
     */
    private int[] receivePTs = new int[0];

    /**
     * The list of RTP SSRCs received on this <tt>Channel</tt>. An element at
     * an even index represents a SSRC and its consecutive element at an odd
     * index specifies the time in milliseconds when the SSRC was last seen (in
     * order to enable timing out SSRCs).
     *
     * Note that the <tt>MediaStream</tt> has the concept of "Remote Source IDs"
     * which is used the same way we use receive SSRCs here in the
     * <tt>RtpChannel</tt>. So in theory we should be able to get rid of one of
     * the two. TAG(cat4-remote-ssrc-hurricane).
     */
    private long[] receiveSSRCs = NO_RECEIVE_SSRCS;

    /**
     * The object used to synchronize access to {@link #receiveSSRCs} and
     * {@link #signaledSSRCs}.
     */
    private final Object receiveSSRCsSyncRoot = new Object();

    /**
     * The type of RTP-level relay (in the terms specified by RFC 3550
     * &quot;RTP: A Transport Protocol for Real-Time Applications&quot; in
     * section 2.3 &quot;Mixers and Translators&quot;) used for this
     * <tt>Channel</tt>.
     */
    private RTPLevelRelayType rtpLevelRelayType;

    /**
     * THe <tt>MediaStream</tt> which this <tt>Channel</tt> adapts to the terms
     * of Jitsi Videobridge and which adapts this <tt>Channel</tt> to the terms
     * of <tt>neomedia</tt>.
     */
    private MediaStream stream;

    /**
     * Used to synchronize access to {@link #stream}.
     */
    private final Object streamSyncRoot = new Object();

    /**
     * Whether {@link #stream} has been closed.
     */
    private boolean streamClosed = false;

    /**
     * The <tt>PropertyChangeListener</tt> which listens to changes of the
     * values of {@link #stream}'s properties.
     */
    private final PropertyChangeListener streamPropertyChangeListener
        = new PropertyChangeListener()
                {
                    @Override
                    public void propertyChange(PropertyChangeEvent ev)
                    {
                        streamPropertyChange(ev);
                    }
                };

    /**
     * The <tt>SessionAddress</tt> which is or is to be the <tt>target</tt> of
     * {@link #stream}. When <tt>DatagramPacket</tt>s are received through the
     * <tt>DatagramSocket</tt>s of this <tt>Channel</tt>, their first RTP and
     * RTCP sources will determine, respectively, the RTP and RTCP targets.
     * Note: this is effectively used for RAW UDP only. With ICE, ice4j takes
     * care of verifying the source and setting the actual target for us.
     * TODO: Maybe move this to RawUdpTransportManager (and stop using the
     * SessionAddress class from FMJ).
     */
    private final SessionAddress streamTarget = new SessionAddress();

    /**
     * The <tt>TransformEngine</tt> of this <tt>RtpChannel</tt>.
     */
    RtpChannelTransformEngine transformEngine = null;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Whether this {@link RtpChannel} should latch on to the remote address of
     * the first received data packet (and control packet) and only received
     * subsequent packets from this remote address.
     * We want to enforce this if RAW-UDP is used. When ICE is used, ice4j does
     * the filtering for us.
     */
    private boolean verifyRemoteAddress = true;

    /**
     * The instance which holds statistics for this {@link RtpChannel}instance.
     */
    protected final Statistics statistics = new Statistics();

    /**
     * Initializes a new <tt>Channel</tt> instance which is to have a specific
     * ID. The initialization is to be considered requested by a specific
     * <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>RtpChannel</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     * @param transportNamespace the namespace of the transport to be used by
     * the new instance. Can be either
     * {@link IceUdpTransportPacketExtension#NAMESPACE} or
     * {@link RawUdpTransportPacketExtension#NAMESPACE}.
     * @param initiator the value to use for the initiator field, or
     * <tt>null</tt> to use the default value.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public RtpChannel(
            Content content,
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

        /*
         * In the case of content mixing, each Channel has its own local
         * synchronization source identifier (SSRC), which Jitsi Videobridge
         * pre-announces.
         */
        initialLocalSSRC = Videobridge.RANDOM.nextLong() & 0xffffffffL;

        conferenceSpeechActivity
            = getContent().getConference().getSpeechActivity();
        if (conferenceSpeechActivity != null)
        {
            /*
             * The PropertyChangeListener will weakly reference this instance
             * and will unregister itself from the conference sooner or later.
             */
             conferenceSpeechActivity.addPropertyChangeListener(
                     propertyChangeListener);
        }

        content.addPropertyChangeListener(propertyChangeListener);

        if (IceUdpTransportPacketExtension.NAMESPACE.equals(
                        this.transportNamespace))
        {
            this.verifyRemoteAddress = false;
        }

        touch();
    }

    /**
     * Determines whether a specific <tt>DatagramPacket</tt> received on the
     * control <tt>DatagramSocket</tt> of this <tt>Channel</tt> is to be
     * accepted for further processing within Jitsi Videobridge or is to be
     * rejected/dropped. When the first <tt>DatagramPacket</tt> arrives, makes
     * its source the only acceptable one for the control
     * <tt>DatagramSocket</tt> and uses it as the control target of
     * {@link #stream}.
     *
     * @param p the <tt>DatagramPacket</tt> received on the control
     * <tt>DatagramSocket</tt> of this <tt>Channel</tt>
     * @return <tt>true</tt> if the specified <tt>DatagramPacket</tt> is to be
     * accepted for further processing within Jitsi Videobridge or
     * <tt>false</tt> to reject/drop it
     */
    private boolean acceptControlInputStreamDatagramPacket(DatagramPacket p)
    {
        InetAddress ctrlAddr = streamTarget.getControlAddress();
        int ctrlPort = streamTarget.getControlPort();
        boolean accept;

        if (ctrlAddr == null)
        {
            streamTarget.setControlHostAddress(p.getAddress());
            streamTarget.setControlPort(p.getPort());

            InetAddress dataAddr = streamTarget.getDataAddress();
            int dataPort = streamTarget.getDataPort();

            if (dataAddr != null)
            {
                ctrlAddr = streamTarget.getControlAddress();
                ctrlPort = streamTarget.getControlPort();

                stream.setTarget(
                        new MediaStreamTarget(
                                dataAddr, dataPort,
                                ctrlAddr, ctrlPort));
            }

            accept = true;
        }
        else
        {
            accept
                = !verifyRemoteAddress ||
                (ctrlAddr.equals(p.getAddress()) && (ctrlPort == p.getPort()));
        }

        if (accept)
        {
            // Note that this Channel is still active.
            touch(ActivityType.PAYLOAD /* control received */);

            /*
             * Does the data of the specified DatagramPacket resemble (a header
             * of) an RTCP packet?
             */
            if (p.getLength() > RTCPHeader.SIZE)
            {
                byte[] data = p.getData();
                int offset = p.getOffset();
                byte b0 = data[offset];

                if ((/* v */ ((b0 & 0xc0) >>> 6) == 2)
                        && (/* pt */ (data[offset + 1] & 0xff)
                                == /* BYE */ 203))
                {
                    int sc = b0 & 0x1f;

                    if (sc > 0)
                    {
                        /*
                         * The focus who has organized the telephony conference
                         * of this Channel receives the RTP streams of the
                         * participants on a single Channel. Consequently, it is
                         * unable to associate the participants with the SSRCs
                         * of the RTP streams that they send. The information
                         * will be provided to the focus by the Jitsi
                         * Videobridge server.
                         */
                        int ssrc = RTPTranslatorImpl.readInt(data, offset + 4);

                        if (removeReceiveSSRC(ssrc))
                            notifyFocus();
                    }
                }
            }
        }

        return accept;
    }

    /**
     * Determines whether a specific <tt>DatagramPacket</tt> received on the
     * data <tt>DatagramSocket</tt> of this <tt>Channel</tt> is to be accepted
     * for further processing within Jitsi Videobridge or is to be
     * rejected/dropped. When the first <tt>DatagramPacket</tt> arrives, makes
     * its source the only acceptable one for the data <tt>DatagramSocket</tt>
     * and uses it as the data target of {@link #stream}.
     *
     * @param p the <tt>DatagramPacket</tt> received on the data
     * <tt>DatagramSocket</tt> of this <tt>Channel</tt>
     * @return <tt>true</tt> if the specified <tt>DatagramPacket</tt> is to be
     * accepted for further processing within Jitsi Videobridge or
     * <tt>false</tt> to reject/drop it
     */
    protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p)
    {
        InetAddress dataAddr = streamTarget.getDataAddress();
        int dataPort = streamTarget.getDataPort();
        boolean accept;

        if (dataAddr == null)
        {
            streamTarget.setDataHostAddress(p.getAddress());
            streamTarget.setDataPort(p.getPort());
            dataAddr = streamTarget.getDataAddress();
            dataPort = streamTarget.getDataPort();

            InetAddress ctrlAddr = streamTarget.getControlAddress();
            int ctrlPort = streamTarget.getControlPort();
            MediaStreamTarget newStreamTarget;

            if (ctrlAddr == null)
            {
                newStreamTarget
                    = new MediaStreamTarget(
                            new InetSocketAddress(dataAddr, dataPort),
                            null);
            }
            else
            {
                newStreamTarget
                    = new MediaStreamTarget(
                            dataAddr, dataPort,
                            ctrlAddr, ctrlPort);
            }
            stream.setTarget(newStreamTarget);

            accept = true;
        }
        else
        {
            accept
                = !verifyRemoteAddress ||
                (dataAddr.equals(p.getAddress()) && (dataPort == p.getPort()));
        }

        if (accept)
        {
            // Note that this Channel is still active.
            touch(ActivityType.PAYLOAD /* data received */);

            /*
             * Does the data of the specified DatagramPacket resemble (a header
             * of) an RTP packet?
             */
            if (p.getLength() >= RTPHeader.SIZE)
            {
                byte[] data = p.getData();
                int off = p.getOffset();
                int v = ((data[off] & 0xc0) >>> 6);

                /*
                 * Prior to adding support for DTLS-SRTP to Jitsi Videobridge,
                 * the SrtpControl of the MediaStream of this Channel was set to
                 * a ZrtpControl. Consequently, the RTP PacketTransformer of
                 * ZrtpControlImpl used to swallow the ZRTP messages. For the
                 * purposes of compatibility, do not accept the ZRTP messages.
                 */
                if (v == 0)
                {
                    if ((data[off] & 0x10) /* x */ == 0x10)
                    {
                        byte[] zrtpMagicCookie = ZrtpRawPacket.ZRTP_MAGIC;

                        if ((data[off + 4] == zrtpMagicCookie[0])
                                && (data[off + 5] == zrtpMagicCookie[1])
                                && (data[off + 6] == zrtpMagicCookie[2])
                                && (data[off + 7] == zrtpMagicCookie[3]))
                        {
                            accept = false;
                        }
                    }
                }
                else if (v == 2)
                {
                    /*
                     * The focus who has organized the telephony conference of
                     * this Channel receives the RTP streams of the participants
                     * on a single Channel. Consequently, it is unable to
                     * associate the participants with the SSRCs of the RTP
                     * streams that they send. The information will be provided
                     * to the focus by the Jitsi Videobridge server.
                     */
                    int ssrc = RTPTranslatorImpl.readInt(data, off + 8);
                    boolean notify;

                    try
                    {
                        notify = addReceiveSSRC(ssrc, true);
                    }
                    catch (SizeExceededException see)
                    {
                        // Drop the packet and do *not* trigger signalling
                        // because of it.
                        accept = false;
                        notify = false;
                    }

                    /*
                     * If a new SSRC has been detected on this channel, and a
                     * Recorder is running, it needs to be notified, so that it
                     * can map the new SSRC to an endpoint.
                     */
                    if (notify && getContent().isRecording())
                    {
                        Recorder recorder = getContent().getRecorder();

                        if (recorder != null)
                        {
                            Endpoint endpoint = getEndpoint();

                            if (endpoint != null)
                            {
                                Synchronizer synchronizer
                                    = recorder.getSynchronizer();

                                if (synchronizer != null)
                                {
                                    synchronizer.setEndpoint(
                                            ssrc & 0xffffffffL,
                                            endpoint.getID());
                                }
                            }
                        }
                    }

                    /*
                     * When performing content mixing (rather than RTP
                     * translation/relay), the MediaStream needs a MediaFormat
                     * to be set in order to make it capable of sending media
                     * data.
                     */
                    if (RTPLevelRelayType.MIXER.equals(getRTPLevelRelayType()))
                    {
                        Map<Byte, MediaFormat> payloadTypes
                            = stream.getDynamicRTPPayloadTypes();

                        if (payloadTypes != null)
                        {
                            int pt = data[off + 1] & 0x7f;
                            MediaFormat format = payloadTypes.get((byte) pt);

                            if ((format != null)
                                    && !format.equals(stream.getFormat()))
                            {
                                stream.setFormat(format);
                                synchronized (streamSyncRoot)
                                {   // otherwise races with stream.start()
                                    stream.setDirection(MediaDirection.SENDRECV);
                                }
                                notify = true;
                            }
                        }
                    }

                    if (notify)
                        notifyFocus();
                }
            }
        }

        return accept;
    }

    /**
     * Adds a specific RTP SSRC to the list of SSRCs received on this
     * <tt>Channel</tt>. If the specified <tt>receiveSSRC</tt> is in the list
     * already, the method does not add it but updates the time at which it was
     * last seen.
     *
     * @param receiveSSRC the RTP SSRC to be added to the list of SSRCs received
     * on this <tt>Channel</tt>
     * @param checkLimit whether to check whether the number of receive SSRCs
     * for the channel exceed the limit ({@link #MAX_RECEIVE_SSRCS}).
     * @return <tt>true</tt> if <tt>receiveSSRC</tt> was added to the list
     * (i.e. was not previously there); otherwise, <tt>false</tt>
     * @throws org.jitsi.videobridge.RtpChannel.SizeExceededException if
     * {@code checkLimit} is true and the number of SSRCs in {@link
     * #receiveSSRCs} would have exceeded {@link #MAX_RECEIVE_SSRCS} with the
     * addition of the new SSRC.
     */
    private boolean addReceiveSSRC(int receiveSSRC,
                                                boolean checkLimit)
        throws SizeExceededException
    {
        synchronized (receiveSSRCsSyncRoot)
        {

        long now = System.currentTimeMillis();

        // contains
        final int length = receiveSSRCs.length;

        for (int i = 0; i < length; i += 2)
        {
            if (((int) receiveSSRCs[i]) == receiveSSRC)
            {
                receiveSSRCs[i + 1] = now;
                /*
                 * The update of the time at which the specified receiveSSRC was
                 * last seen does not constitute a change in the value of the
                 * receiveSSRCs property of this instance.
                 */
                return false;
            }
        }

        if (checkLimit && length >= MAX_RECEIVE_SSRCS / 2)
        {
            throw new SizeExceededException();
        }

        // add
        long[] newReceiveSSRCs = new long[length + 2];

        System.arraycopy(receiveSSRCs, 0, newReceiveSSRCs, 0, length);
        newReceiveSSRCs[length] = 0xFFFFFFFFL & receiveSSRC;
        newReceiveSSRCs[length + 1] = now;
        receiveSSRCs = newReceiveSSRCs;

        return true;

        } // synchronized (receiveSSRCsSyncRoot)
    }

    /**
     * Asks this <tt>Channel</tt> to request keyframes in the RTP video streams
     * that it receives.
     */
    void askForKeyframes()
    {
        askForKeyframes(getReceiveSSRCs());
    }

    /**
     * Asks this <tt>Channel</tt> to request keyframes in the RTP video streams
     * that it receives.
     *
     * @param receiveSSRCs the SSRCs to request an FIR for.
     */
    public void askForKeyframes(int[] receiveSSRCs)
    {
        // XXX(gp) does it make sense to repeatedly request key frames when we
        // haven't received a key frame for a previous request? In some cases,
        // maybe (the key frame might have been lost for example). This should
        // be more intelligent.
        if (receiveSSRCs != null && receiveSSRCs.length != 0)
        {
            RTCPFeedbackMessageSender rtcpFeedbackMessageSender
                = getContent().getRTCPFeedbackMessageSender();

            if (rtcpFeedbackMessageSender != null)
                rtcpFeedbackMessageSender.sendFIR(receiveSSRCs);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void closeStream()
    {
        if (!streamClosed)
        {
            MediaStreamStats2 mss = stream.getMediaStreamStats();
            statistics.bytesReceived = mss.getReceiveStats().getBytes();
            statistics.bytesSent = mss.getSendStats().getBytes();
            statistics.packetsReceived = mss.getReceiveStats().getPackets();
            statistics.packetsSent = mss.getSendStats().getPackets();
            stream.setProperty(RtpChannel.class.getName(), null);
            removeStreamListeners();
            stream.close();

            streamClosed = true;
        }
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.ChannelCommon</tt> to the values of the
     * respective properties of this instance. Thus, the specified <tt>iq</tt>
     * may be thought of as a description of this instance.
     *
     * @param commonIq the <tt>ColibriConferenceIQ.ChannelCommon</tt> on which
     * to set the values of the properties of this instance.
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;

        // FIXME The attribute rtp-level-relay-type/Channel property
        // rtpLevelRelayType is pretty much the most important given that Jitsi
        // Videobridge implements an RTP-level relay. Unfortunately, we do not
        // currently support switching between the different types of RTP-level
        // relays. The following is a hack/workaround making sure that the
        // attribute/property in question has a value as soon as necessary and
        // before this Channel is made available to the conference focus for
        // consumption.
        iq.setRTPLevelRelayType(getRTPLevelRelayType());

        super.describe(iq);

        iq.setDirection(stream.getDirection());

        iq.setLastN(null);

        long initialLocalSSRC = getInitialLocalSSRC();

        if (initialLocalSSRC != -1)
        {
            SourcePacketExtension source = new SourcePacketExtension();

            source.setSSRC(initialLocalSSRC);
            iq.addSource(source);
        }
        iq.setSSRCs(getReceiveSSRCs());
    }

    /**
     * Notifies this instance that {@link #conferenceSpeechActivity} has
     * identified a speaker switch event in the multipoint conference and there
     * is now a new dominant speaker.
     */
    protected void dominantSpeakerChanged()
    {
    }

    /**
     * Gets the <tt>RtpChannelDatagramFilter</tt> that accepts RTP (if
     * <tt>rtcp</tt> is false) or RTCP (if <tt>rtcp</tt> is true) packets for
     * this <tt>RtpChannel</tt>.
     * @param rtcp whether to return the filter for RTP or RTCP packets.
     * @return the <tt>RtpChannelDatagramFilter</tt> that accepts RTP (if
     * <tt>rtcp</tt> is false) or RTCP (if <tt>rtcp</tt> is true) packets for
     * this <tt>RtpChannel</tt>.
     */
    RtpChannelDatagramFilter getDatagramFilter(boolean rtcp)
    {
        RtpChannelDatagramFilter datagramFilter;
        int index = rtcp ? 1 : 0;

        synchronized (datagramFilters)
        {
            datagramFilter = datagramFilters[index];
            if (datagramFilter == null)
            {
                datagramFilters[index]
                    = datagramFilter
                        = new RtpChannelDatagramFilter(this, rtcp);
            }
        }
        return datagramFilter;
    }

    /**
     * Gets the local synchronization source identifier (SSRC) to be
     * pre-announced in the case of content mixing and not in the case of RTP
     * translation.
     *
     * @return the local synchronization source identifier (SSRC) to be
     * pre-announced in the case of content mixing; <tt>-1</tt>, otherwise
     */
    private long getInitialLocalSSRC()
    {
        switch (getRTPLevelRelayType())
        {
        case MIXER:
            return initialLocalSSRC;
        case TRANSLATOR:
            return getContent().getInitialLocalSSRC();
        default:
            return -1;
        }
    }

    /**
     * Returns a <tt>MediaService</tt> implementation (if any).
     *
     * @return a <tt>MediaService</tt> implementation (if any).
     */
    private MediaService getMediaService()
    {
        return getContent().getMediaService();
    }

    /**
     * Gets a list of the RTP SSRCs received on this <tt>Channel</tt>.
     *
     * @return an array of <tt>int</tt>s which represents a list of the RTP
     * SSRCs received on this <tt>Channel</tt>
     */
    public int[] getReceiveSSRCs()
    {
        // this.receiveSSRCs is copy-on-write.
        long[] receiveSSRCsField = this.receiveSSRCs;
        int length = receiveSSRCsField.length;

        if (length == 0)
        {
            return ColibriConferenceIQ.NO_SSRCS;
        }
        else
        {
            int[] receiveSSRCs = new int[length / 2];

            for (int src = 0, dst = 0; src < length; src += 2, dst++)
            {
                receiveSSRCs[dst] = (int) receiveSSRCsField[src];
            }
            return receiveSSRCs;
        }
    }

    /**
     * Gets the type of RTP-level relay (in the terms specified by RFC 3550
     * "RTP: A Transport Protocol for Real-Time Applications" in section 2.3
     * "Mixers and Translators") used for this <tt>Channel</tt>.
     *
     * @return the type of RTP-level relay (in the terms specified by RFC 3550
     * "RTP: A Transport Protocol for Real-Time Applications" in section 2.3
     * "Mixers and Translators") used for this <tt>Channel</tt>
     */
    public RTPLevelRelayType getRTPLevelRelayType()
    {
        /*
         * Jitsi Videobridge implements an RTP-level relay and it always knows
         * the type of RTP-level relay that it implements. In other words, make
         * sure that a default value is chosen if no explicit value has been
         * specified yet.
         */
        if (rtpLevelRelayType == null)
            setRTPLevelRelayType(RTPLevelRelayType.TRANSLATOR);
        return rtpLevelRelayType;
    }

    /**
     * Returns the <tt>MediaStream</tt> which this <tt>Channel</tt> adapts to
     * the terms of Jitsi Videobridge and which adapts this <tt>Channel</tt>
     * to the terms of <tt>neomedia</tt>.
     *
     * @return the <tt>MediaStream</tt> instance
     */
    public MediaStream getStream()
    {
        return stream;
    }

    /**
     * Returns the {@code SessionAddress} which is or is to be the
     * {@code target} of {@link #stream}. When {@code DatagramPacket}s are
     * received through the {@code DatagramSocket}s of this {@code Channel},
     * their first RTP and RTCP sources will determine, respectively, the RTP
     * and RTCP targets.
     *
     * @return the {@code SessionAddress} which is or is to be the
     * {@code target} of {@link #stream}
     */
    public SessionAddress getStreamTarget()
    {
        return streamTarget;
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

    void initialize(RTPLevelRelayType rtpLevelRelayType)
        throws IOException
    {
        super.initialize();

        MediaService mediaService = getMediaService();
        MediaType mediaType = getContent().getMediaType();

        synchronized (streamSyncRoot)
        {
            stream
                = mediaService.createMediaStream(
                        null,
                        mediaType,
                        getDtlsControl());

             // Add the PropertyChangeListener to the MediaStream prior to
             // performing further initialization so that we do not miss changes
             // to the values of properties we may be interested in.
            stream.addPropertyChangeListener(streamPropertyChangeListener);
            stream.setName(getID());
            stream.setProperty(RtpChannel.class.getName(), this);
            if (transformEngine != null)
            {
                stream.setExternalTransformer(transformEngine);
            }

            logger.info(Logger.Category.STATISTICS,
                        "create_stream," + getLoggingId());

            /*
             * The attribute rtp-level-relay-type specifies the
             * vale of pretty much the most important Channel
             * property given that Jitsi Videobridge implements
             * an RTP-level relay. Consequently, it is
             * intuitively a sign of common sense to take the
             * value into account as possible.
             *
             * The attribute rtp-level-relay-type is optional.
             * If a value is not specified, then the Channel
             * rtpLevelRelayType is to not be changed.
             */
            if (rtpLevelRelayType != null)
                setRTPLevelRelayType(rtpLevelRelayType);

            // The transport manager could be already connected, in which case
            // (since we just created the stream), any previous calls to
            // transportConnected() have failed started the stream. So trigger
            // one now, to make sure that the stream is started.
            TransportManager transportManager = getTransportManager();
            if (transportManager != null && transportManager.isConnected())
            {
                transportConnected();
            }
        }
    }

    /**
     * Initializes the <tt>RtpChannelTransformEngine</tt> that will be used by
     * this <tt>RtpChannel</tt>.
     *
     * @return the just created <tt>RtpChannelTransformEngine</tt> instance.
     */
    RtpChannelTransformEngine initializeTransformerEngine()
    {
        transformEngine = new RtpChannelTransformEngine(this);
        if (stream != null)
        {
            stream.setExternalTransformer(transformEngine);
        }
        return transformEngine;
    }

    /**
     * Starts {@link #stream} if it has not been started yet and if the state of
     * this <tt>Channel</tt> meets the prerequisites to invoke
     * {@link MediaStream#start()}. For example, <tt>MediaStream</tt> may be
     * started only after a <tt>StreamConnector</tt> has been set on it and this
     * <tt>Channel</tt> may be able to provide a <tt>StreamConnector</tt> only
     * after the transport manager has completed the connectivity establishment.
     *
     * @throws IOException if anything goes wrong while starting <tt>stream</tt>
     */
    @Override
    protected void maybeStartStream()
        throws IOException
    {
        // The stream hasn't been initialized yet.
        synchronized (streamSyncRoot)
        {
            if (stream == null)
                return;
        }

        RetransmissionRequester retransmissionRequester
            = stream.getRetransmissionRequester();
        if (retransmissionRequester != null)
            retransmissionRequester.setSenderSsrc(getContent().getInitialLocalSSRC());

        MediaStreamTarget streamTarget = createStreamTarget();
        StreamConnector connector = getStreamConnector();
        if (connector == null)
        {
            logger.info("Not starting stream, connector is null");
            return;
        }

        if (streamTarget != null)
        {
            InetSocketAddress dataAddr = streamTarget.getDataAddress();
            if (dataAddr == null)
            {
                logger.info(
                        "Not starting stream, the target's data address is null");
                return;
            }

            this.streamTarget.setDataHostAddress(dataAddr.getAddress());
            this.streamTarget.setDataPort(dataAddr.getPort());

            InetSocketAddress ctrlAddr = streamTarget.getControlAddress();
            if (ctrlAddr != null)
            {
                this.streamTarget.setControlHostAddress(ctrlAddr.getAddress());
                this.streamTarget.setControlPort(ctrlAddr.getPort());
            }

            stream.setTarget(streamTarget);
        }
        stream.setConnector(connector);

        Content content = getContent();
        Conference conference = content.getConference();

        if (!stream.isStarted())
        {
            /*
             * We've postponed the invocation of the method
             * getRTPLevelRelayType() in order to make sure that the conference
             * focus has had a chance to set the RTP-level relay type. We have
             * to invoke the method MediaStream#setSSRCFactory(SSRCFactory)
             * before starting the stream.
             */
            if (RTPLevelRelayType.MIXER.equals(getRTPLevelRelayType()))
                stream.setSSRCFactory(new SSRCFactoryImpl(initialLocalSSRC));

            synchronized (streamSyncRoot) // Otherwise, races with stream.setDirection().
            {
                stream.start();
            }

            EventAdmin eventAdmin = conference.getEventAdmin();
            if (eventAdmin != null)
            {
                eventAdmin.sendEvent(EventFactory.streamStarted(this));
            }
        }

        if (logger.isTraceEnabled())
        {
            logger.debug(Logger.Category.STATISTICS,
                       "ch_direction," + getLoggingId()
                        + " direction=" + stream.getDirection());
        }
    }

    /**
     * Called when new <tt>Endpoint</tt> is being set on this <tt>Channel</tt>.
     * Notifies the focus of this <tt>Channel</tt>'s <tt>Conference</tt> about
     * the current state of this <tt>Channel</tt>.
     */
    private void notifyFocus()
    {
        Content content = getContent();
        Conference conference = content.getConference();
        String focus = conference.getFocus();

        if (focus == null)
            return;

        Collection<ComponentImpl> components
            = conference.getVideobridge().getComponents();

        if (!components.isEmpty())
        {
            try
            {
                ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();

                conference.describeShallow(conferenceIQ);

                ColibriConferenceIQ.Content contentIQ
                    = conferenceIQ.getOrCreateContent(content.getName());
                ColibriConferenceIQ.Channel channelIQ
                    = new ColibriConferenceIQ.Channel();

                describe(channelIQ);
                contentIQ.addChannel(channelIQ);

                conferenceIQ.setTo(focus);
                conferenceIQ.setType(org.jivesoftware.smack.packet.IQ.Type.SET);

                for (ComponentImpl component : components)
                    component.send(conferenceIQ);
            }
            catch (Throwable t)
            {
                // A telephony conference will still function, albeit with
                // reduced SSRC-dependent functionality such as audio levels.
                if (t instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                else if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onEndpointChanged(Endpoint oldValue, Endpoint newValue)
    {
        super.onEndpointChanged(oldValue, newValue);

        if (oldValue != null)
        {
            oldValue.removeChannel(this);
            oldValue.removePropertyChangeListener(this);
        }
        if (newValue != null)
        {
            newValue.addChannel(this);
            newValue.addPropertyChangeListener(this);
        }
    }

    /**
     * Notifies this instance that there was a change in the value of a property
     * of an object in which this instance is interested.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the object of
     * interest, the name of the property and the old and new values of that
     * property
     */
    @Override
    public void propertyChange(PropertyChangeEvent ev)
    {
        Object source = ev.getSource();

        if ((conferenceSpeechActivity == source)
                && (conferenceSpeechActivity != null))
        {
            String propertyName = ev.getPropertyName();

            if (ConferenceSpeechActivity.DOMINANT_ENDPOINT_PROPERTY_NAME
                    .equals(propertyName))
            {
                dominantSpeakerChanged();
            }
        }
    }

    /**
     * Removes a specific RTP SSRC from the list of SSRCs received on this
     * <tt>Channel</tt>.
     *
     * @param receiveSSRC the RTP SSRC to be removed from the list of SSRCs
     * received on this <tt>Channel</tt>
     * @return <tt>true</tt> if <tt>receiveSSRC</tt> was found in the list of
     * SSRCs received on this <tt>Channel</tt>; otherwise, <tt>false</tt>
     */
    private boolean removeReceiveSSRC(int receiveSSRC)
    {
        boolean removed = false;

        synchronized (receiveSSRCsSyncRoot)
        {

        final int length = receiveSSRCs.length;

        if (length == 2)
        {
            if (((int) receiveSSRCs[0]) == receiveSSRC)
            {
                receiveSSRCs = NO_RECEIVE_SSRCS;
                removed = true;
            }
        }
        else
        {
            for (int i = 0; i < length; i += 2)
            {
                if (((int) receiveSSRCs[i]) == receiveSSRC)
                {
                    long[] newReceiveSSRCs = new long[length - 2];

                    if (i != 0)
                    {
                        System.arraycopy(
                                receiveSSRCs, 0,
                                newReceiveSSRCs, 0,
                                i);
                    }
                    if (i != newReceiveSSRCs.length)
                    {
                        System.arraycopy(
                                receiveSSRCs, i + 2,
                                newReceiveSSRCs, i,
                                newReceiveSSRCs.length - i);
                    }
                    receiveSSRCs = newReceiveSSRCs;
                    removed = true;
                    break;
                }
            }
        }

        } // synchronized (receiveSSRCsSyncRoot)

        return removed;
    }

    /**
     * Removes the listeners that this <tt>Channel</tt> has added to
     * {@link #stream} because this <tt>Channel</tt> is expiring.
     * <p>
     * <b>Warning</b>: The procedure must complete without throwing any
     * exceptions; otherwise, the <tt>stream</tt> will not be closed.
     * </p>
     */
    protected void removeStreamListeners()
    {
        try
        {
            stream.removePropertyChangeListener(
                streamPropertyChangeListener);
        }
        catch (Throwable t)
        {
            if (t instanceof InterruptedException)
                Thread.currentThread().interrupt();
            else if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
        }
    }

    /**
     * Notifies this instance that the value of the <tt>rtpLevelRelayType</tt>
     * property of this instance changed from a specific old value to a specific
     * new value. Allows extenders to override.
     *
     * @param oldValue the old value of the <tt>rtpLevelRelayType</tt> property
     * @param newValue the new value of the <tt>rtpLevelRelayType</tt> property
     */
    protected void rtpLevelRelayTypeChanged(
            RTPLevelRelayType oldValue,
            RTPLevelRelayType newValue)
    {
    }

    /**
     * Notifies this instance that the <tt>RTPTranslator</tt> that it uses will
     * write a specific packet/<tt>buffer</tt> from a specific <tt>Channel</tt>.
     * Allows this instance to apply the logic of <tt>lastN</tt> and disallow
     * the translation of the specified packet from the specified source
     * <tt>Channel</tt> into this destination <tt>Channel</tt>.
     *
     * @param data
     * @param buffer
     * @param offset
     * @param length
     * @param source
     * @return <tt>true</tt> to allow the <tt>RTPTranslator</tt> to write the
     * specified packet/<tt>buffer</tt> into this <tt>Channel</tt>; otherwise,
     * <tt>false</tt>
     */
    boolean rtpTranslatorWillWrite(
            boolean data,
            byte[] buffer, int offset, int length,
            RtpChannel source)
    {
        return true;
    }

    /**
     * Notifies this <tt>RtpChannel</tt> that its associated
     * <tt>SctpConnection</tt> has become ready i.e. connected to the remote
     * peer and operational.
     *
     * @param endpoint the <tt>Endpoint</tt> which is the source of the
     * notification and through which an <tt>SctpConnection</tt> is associated
     * with this <tt>RtpChannel</tt>
     */
    void sctpConnectionReady(Endpoint endpoint)
    {
    }

    /**
     * Enables or disables the adaptive lastN functionality.
     *
     * Does nothing, allows extenders to implement.
     *
     * @param adaptiveLastN <tt>true</tt> to enable and <tt>false</tt> to
     * disable adaptive lastN.
     */
    public void setAdaptiveLastN(boolean adaptiveLastN)
    {}

    /**
     * Enables or disables the adaptive simulcast functionality.
     *
     * Does nothing, allows extenders to implement.
     *
     * @param adaptiveSimulcast <tt>true</tt> to enable and <tt>false</tt> to
     * disable adaptive simulcast.
     */
    public void setAdaptiveSimulcast(boolean adaptiveSimulcast)
    {}

    /**
     * Sets the direction of the <tt>MediaStream</tt> of this <tt>Channel</tt>.
     * <p>
     * <b>Warning</b>: The method does nothing if latching has not finished.
     * </p>
     *
     * @param direction the <tt>MediaDirection</tt> to set on the
     * <tt>MediaStream</tt> of this <tt>Channel</tt>
     */
    public void setDirection(MediaDirection direction)
    {
        // XXX We modify the stream direction only after latching has finished.
        if (streamTarget.getDataAddress() != null)
            stream.setDirection(direction);

        touch(); // It seems this Channel is still active.
    }

    /**
     * Sets the maximum number of video RTP streams to be sent from Jitsi
     * Videobridge to the endpoint associated with this video <tt>Channel</tt>.
     *
     * @param lastN the maximum number of video RTP streams to be sent from
     * Jitsi Videobridge to the endpoint associated with this video
     * <tt>Channel</tt>
     */
    public void setLastN(Integer lastN)
    {
        // The attribute/functionality last-n is defined/effective for video
        // channels only.
    }

    /**
     * Sets the payload types (i.e. the <tt>MediaFormat</tt>s) to be used by
     * this <tt>Channel</tt>.
     *
     * @param payloadTypes the <tt>PayloadTypePacketExtension</tt>s which
     * specify the payload types (i.e. the <tt>MediaFormat</tt>s) to be used by
     * this <tt>Channel</tt>
     */
    public void setPayloadTypes(List<PayloadTypePacketExtension> payloadTypes)
    {
        /*
         * Convert the PayloadTypePacketExtensions into Formats and let the
         * MediaStream know about them.
         */
        if ((payloadTypes != null) && (payloadTypes.size() > 0))
        {
            MediaService mediaService = getMediaService();

            if (mediaService != null)
            {
                int payloadTypeCount = payloadTypes.size();

                receivePTs = new int[payloadTypeCount];
                stream.clearDynamicRTPPayloadTypes();
                for (int i = 0; i < payloadTypeCount; i++)
                {
                    PayloadTypePacketExtension payloadType
                        = payloadTypes.get(i);

                    receivePTs[i] = payloadType.getID();

                    MediaFormat mediaFormat
                        = JingleUtils.payloadTypeToMediaFormat(
                                payloadType,
                                mediaService,
                                null);

                    if (mediaFormat != null)
                    {
                        stream.addDynamicRTPPayloadType(
                                (byte) payloadType.getID(),
                                mediaFormat);
                    }
                }

                TransportManager transportManager = getTransportManager();
                if (transportManager != null)
                {
                    transportManager.payloadTypesChanged(this);
                }

                RtxTransformer rtxTransformer;
                if (transformEngine != null && (rtxTransformer
                    = transformEngine.getRtxTransformer()) != null)
                {
                    rtxTransformer.onDynamicPayloadTypesChanged();
                }
            }

        }

        touch(); // It seems this Channel is still active.
    }

    /**
     * Sets the RTP header extensions to be used by this <tt>Channel</tt>.
     *
     * @param rtpHeaderExtensions the <tt>RTPHdrExtPacketExtension</tt>s which
     * specify the RTP header extensions and their associated IDs to be used by
     * this <tt>Channel</tt>
     */
    public void setRtpHeaderExtensions(
            Collection<RTPHdrExtPacketExtension> rtpHeaderExtensions)
    {
        if ((rtpHeaderExtensions != null) && (rtpHeaderExtensions.size() > 0))
        {
            for (RTPHdrExtPacketExtension rtpHdrExtPacketExtension
                    : rtpHeaderExtensions)
            {
                addRtpHeaderExtension(rtpHdrExtPacketExtension);
            }
        }
    }

    /**
     * Adds the <tt>RTPExtension</tt> described in
     * <tt>rtpHdrExtPacketExtension</tt>to this <tt>channel</tt>'s
     * <tt>MediaStream</tt>
     * @param rtpHdrExtPacketExtension the <tt>RTPHdrExtPacketExtension</tt>
     * which describes the <tt>RTPExtension</tt> to be added to this
     * <tt>channel</tt>.
     */
    protected void addRtpHeaderExtension(
            RTPHdrExtPacketExtension rtpHdrExtPacketExtension)
    {
        URI uri = rtpHdrExtPacketExtension.getURI();
        if (uri == null)
        {
            logger.warn(
                    "Failed to add an RTP header extension with an invalid"
                        + " URI: "
                        + rtpHdrExtPacketExtension.getAttribute(
                                RTPHdrExtPacketExtension.URI_ATTR_NAME));
            return;
        }

        Byte id = Byte.valueOf(rtpHdrExtPacketExtension.getID());
        if (id == null)
        {
            logger.warn(
                    "Failed to add an RTP header extension with an invalid ID: "
                        + rtpHdrExtPacketExtension.getID());
            return;
        }

        if (RTPExtension.ABS_SEND_TIME_URN.equals(uri.toString()))
        {
            ConfigurationService cfg
                = ServiceUtils.getService(
                        getBundleContext(),
                        ConfigurationService.class);

            if (cfg != null
                    && cfg.getBoolean(DISABLE_ABS_SEND_TIME_PNAME, false))
            {
                return;
            }
        }

        // It is safe to just add it, MediaStream will take care of duplicates.
        MediaStream stream = getStream();
        if (stream != null)
            stream.addRTPExtension(id, new RTPExtension(uri));
    }

    /**
     * Sets the type of RTP-level relay (in the terms specified by RFC 3550
     * "RTP: A Transport Protocol for Real-Time Applications" in section 2.3
     * "Mixers and Translators") to use for this <tt>Channel</tt>.
     *
     * @param rtpLevelRelayType the type of RTP-level relay (in the terms
     * specified by RFC 3550 "RTP: A Transport Protocol for Real-Time
     * Applications" in section 2.3 "Mixers and Translators") to use for this
     * <tt>Channel</tt>
     */
    public void setRTPLevelRelayType(RTPLevelRelayType rtpLevelRelayType)
    {
        if (rtpLevelRelayType == null)
            throw new NullPointerException("rtpLevelRelayType");

        if (this.rtpLevelRelayType == null)
        {
            RTPLevelRelayType oldValue = null;

            this.rtpLevelRelayType = rtpLevelRelayType;

            RTPLevelRelayType newValue = getRTPLevelRelayType();

            /*
             * If the RTP-level relay to be used for this Channel is a mixer,
             * then the stream will have a MediaDevice and will not have an
             * RTPTranslator. If the RTP-level relay to be used for this Channel
             * is a translator, then the stream will not have a MediaDevice and
             * will have an RTPTranslator.
             */
            switch (newValue)
            {
            case MIXER:
                MediaDevice device = getContent().getMixer();

                stream.setDevice(device);

                /*
                 * It is necessary to start receiving media in order to
                 * determine the MediaFormat in which the stream will send the
                 * media it generates.
                 */
                if (stream.getFormat() == null)
                    stream.setDirection(MediaDirection.RECVONLY);
                break;

            case TRANSLATOR:
                stream.setRTPTranslator(getContent().getRTPTranslator());
                break;

            default:
                throw new IllegalStateException("rtpLevelRelayType");
            }

            rtpLevelRelayTypeChanged(oldValue, newValue);
        }
        else if (!this.rtpLevelRelayType.equals(rtpLevelRelayType))
        {
            /*
             * TODO The implementation of Channel at the time of this writing
             * does not support switching between the various types of RTP-level
             * replays.
             */
        }

        touch(); // It seems this Channel is still active.
    }

    /**
     * Notifies this instance that {@link #conferenceSpeechActivity} has ordered
     * the <tt>Endpoint</tt>s in the multipoint conference in which this
     * <tt>Channel</tt> is participating and the new order may affect the set of
     * <tt>lastN</tt> <tt>Endpoint</tt>s translated to the remote endpoint of
     * this <tt>Channel</tt>.
     *
     * @param endpoints the ordered list of <tt>Endpoint</tt>s reported by
     * <tt>conferenceSpeechActivity</tt>
     * @return a list of the <tt>Endpoint</tt>s which should be asked for
     * (video) keyframes because, for example, they are entering the set of
     * <tt>lastN</tt> <tt>Endpoint</tt>s of this <tt>Channel</tt>, or
     * {@code null} if there are no such endpoints.
     */
    List<Endpoint> speechActivityEndpointsChanged(List<Endpoint> endpoints)
    {
        // The attribute/functionality last-n is defined/effective for video
        // channels only.
        return null;
    }

    /**
     * Notifies this <tt>Channel</tt> that the value of a property of
     * {@link #stream} has changed from a specific old value to a specific new
     * value.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the name of the
     * property which had its value changed and the old and new values of that
     * property
     */
    private void streamPropertyChange(PropertyChangeEvent ev)
    {
        /*
         * XXX The following is based on an internal property which has been
         * introduced specifically for Jitsi Videobridge and should not be used
         * anywhere else because it will likely be removed.
         */
        String propertyName = ev.getPropertyName();
        String prefix = MediaStreamImpl.class.getName() + ".rtpConnector";

        if (propertyName.startsWith(prefix))
        {
            String rtpConnectorPropertyName
                = propertyName.substring(prefix.length());
            Object newValue = ev.getNewValue();

            if (rtpConnectorPropertyName.equals(""))
            {
                if (newValue instanceof RTPConnector)
                {
                    streamRTPConnectorChanged(
                            (RTPConnector) ev.getOldValue(),
                            (RTPConnector) newValue);
                }
            }
            else if (newValue instanceof RTPConnectorInputStream)
            {
                DatagramPacketFilter datagramPacketFilter;

                if (rtpConnectorPropertyName.equals(".controlInputStream"))
                {
                    datagramPacketFilter
                        = new DatagramPacketFilter()
                        {
                            @Override
                            public boolean accept(DatagramPacket p)
                            {
                                return
                                    acceptControlInputStreamDatagramPacket(p);
                            }
                        };
                }
                else if (rtpConnectorPropertyName.equals(".dataInputStream"))
                {
                    datagramPacketFilter
                        = new DatagramPacketFilter()
                        {
                            @Override
                            public boolean accept(DatagramPacket p)
                            {
                                return acceptDataInputStreamDatagramPacket(p);
                            }
                        };
                }
                else
                {
                    datagramPacketFilter = null;
                }
                if (datagramPacketFilter != null)
                {
                    ((RTPConnectorInputStream<?>) newValue)
                        .addDatagramPacketFilter(datagramPacketFilter);
                }
            }
        }
    }

    /**
     * Notifies this <tt>Channel</tt> that the value of the
     * <tt>rtpConnector</tt> property of {@link #stream} has changed from a
     * specific old value to a specific new value. Allows extenders to override
     * the method in order to, for example, configure the <tt>rtpConnector</tt>
     * of <tt>stream</tt>.
     *
     * @param oldValue the old value of the <tt>rtpConnector</tt> property
     * before the change
     * @param newValue the new value of the <tt>rtpConnector</tt> property after
     * the change
     */
    protected void streamRTPConnectorChanged(
            RTPConnector oldValue,
            RTPConnector newValue)
    {
    }

    /**
     * The <tt>Set</tt> of the SSRCs that this <tt>RtpChannel</tt> has signaled.
     */
    private Set<Integer> signaledSSRCs = new HashSet<>();

    /**
     * Sets the <tt>Set</tt> of the SSRCs that this <tt>RtpChannel</tt> has
     * signaled and updates the <tt>Content</tt> SSRCs accordingly.
     *
     * @param sources The <tt>List</tt> of <tt>SourcePacketExtension</tt> that
     * describes the list of sources of this <tt>RtpChannel</tt> and that is
     * used as the input in the update of the Sets the <tt>Set</tt> of the SSRCs
     * that this <tt>RtpChannel</tt> has signaled.
     */
    public void setSources(List<SourcePacketExtension> sources)
    {
        if (sources == null || sources.isEmpty())
            return;

        synchronized (receiveSSRCsSyncRoot)
        {

        Set<Integer> oldSignaledSSRCs = new HashSet<>(signaledSSRCs);

        // Build the set of the newly signaled SSRCs.
        Set<Integer> newSignaledSSRCs = new HashSet<>();
        for (SourcePacketExtension source : sources)
        {
            long ssrc = source.getSSRC();
            if (ssrc != -1)
                newSignaledSSRCs.add((int) ssrc);
        }

        // Add the added SSRCs.
        Set<Integer> addedSSRCs = new HashSet<>(newSignaledSSRCs);
        addedSSRCs.removeAll(oldSignaledSSRCs);
        if (!addedSSRCs.isEmpty())
        {

            Recorder recorder = null;
            Synchronizer synchronizer = null;
            Endpoint endpoint = null;
            if (getContent().isRecording())
            {
                recorder = getContent().getRecorder();
                synchronizer = recorder.getSynchronizer();
                endpoint = getEndpoint();
            }

            for (Integer addedSSRC : addedSSRCs)
            {
                try
                {
                    // Do allow the number of explicitly signalled SSRCs to
                    // exceed the limit.
                    addReceiveSSRC(addedSSRC, false);

                    // If recording is enabled, we need to add the mapping from
                    // SSRC to EndpointID into the Synchronizer
                    // instance used by RecorderRtpImpl.
                    if (recorder != null && endpoint != null && synchronizer != null)
                    {
                        synchronizer.setEndpoint(addedSSRC & 0xffffffffl,
                            endpoint.getID());
                    }
                }
                catch (SizeExceededException see)
                {
                    // Never thrown with checkLimit=false.
                    logger.error( "An unexpected exception occurred.", see );
                }
            }
        }

        // Remove the removed SSRCs.
        oldSignaledSSRCs.removeAll(newSignaledSSRCs);
        if (!oldSignaledSSRCs.isEmpty())
        {
            for (Integer removedSSRC : oldSignaledSSRCs)
                removeReceiveSSRC(removedSSRC);
        }

        // Set the newly signaled ssrcs.
        signaledSSRCs = newSignaledSSRCs;

        } // synchronized (receiveSSRCsSyncRoot)

        touch(); // It seems this Channel is still active.
    }

    /**
     * Sets the SSRC groupings for this <tt>RtpChannel</tt>.
     * @param sourceGroups
     */
    public void setSourceGroups(List<SourceGroupPacketExtension> sourceGroups)
    {

    }

    /**
     * Returns the RTP Payload Type numbers which this channel is configured
     * to receive.
     * @return the RTP Payload Type numbers which this channel is configured
     * to receive.
     */
    public int[] getReceivePTs()
    {
        return receivePTs;
    }

    /**
     * {@inheritDoc}
     *
     * Closes {@link #transformEngine}. Normally this would be done by
     * {@link #stream} when it is being closed, but we have observed cases (e.g.
     * when running health checks) where it doesn't happen, and since
     * {@link #transformEngine} is part of this {@code RtpChannel}, the latter
     * assumes the responsibility of releasing its resources.
     */
    @Override
    public boolean expire()
    {
        if (!super.expire())
        {
            // Already expired.
            return false;
        }

        if (getContent().getConference().includeInStatistics())
        {
            Conference.Statistics conferenceStatistics
                = getContent().getConference().getStatistics();
            conferenceStatistics.totalChannels.incrementAndGet();

            long lastPayloadActivityTime = getLastPayloadActivityTime();
            long lastTransportActivityTime = getLastTransportActivityTime();

            if (lastTransportActivityTime == 0)
            {
                // Check for ICE failures.
                conferenceStatistics.totalNoTransportChannels.incrementAndGet();
            }

            if (lastPayloadActivityTime == 0)
            {
                // Check for payload.
                conferenceStatistics.totalNoPayloadChannels.incrementAndGet();
            }

            logger.info(Logger.Category.STATISTICS,
                        "expire_ch_stats," + getLoggingId() +
                            " bRecv=" + statistics.bytesReceived +
                            ",bSent=" + statistics.bytesSent +
                            ",pRecv=" + statistics.packetsReceived +
                            ",pSent=" + statistics.packetsSent +
                            ",bRetr=" + statistics.bytesRetransmitted +
                            ",bNotRetr=" + statistics.bytesNotRetransmitted +
                            ",pRetr=" + statistics.packetsRetransmitted +
                            ",pNotRetr=" + statistics.packetsNotRetransmitted +
                            ",pMiss=" + statistics.packetsMissingFromCache);
        }
        TransformEngine transformEngine = this.transformEngine;
        if (transformEngine != null)
        {
            PacketTransformer t = transformEngine.getRTPTransformer();
            if (t != null)
            {
                t.close();
            }

            t = transformEngine.getRTCPTransformer();
            if (t != null)
            {
                t.close();
            }
        }

        return true;
    }

    /**
     * @return the {@link ConferenceSpeechActivity} for this channel.
     */
    public ConferenceSpeechActivity getConferenceSpeechActivity()
    {
        return conferenceSpeechActivity;
    }

    /**
     * Sets a delay of the RTP stream expressed in a number of packets.
     * The property is immutable which means than once set can not be changed
     * later.
     *
     * *NOTE* that this delay is meant to be used only for audio and video
     * synchronization tests and should never be used in production.
     *
     * @param packetDelay tells by how many packets RTP stream should be
     * delayed. Will have effect only if greater than 0.
     *
     * @return <tt>true</tt> if the delay has been set or <tt>false</tt>
     * otherwise.
     */
    public boolean setPacketDelay(int packetDelay)
    {
        RtpChannelTransformEngine engine = this.transformEngine;
        if (engine == null)
        {
            engine = initializeTransformerEngine();
        }
        return engine.setPacketDelay(packetDelay);
    }

    /**
     * Gets the <tt>TransformEngine</tt> of this <tt>RtpChannel</tt>.
     *
     * @return The <tt>TransformEngine</tt> of this <tt>RtpChannel</tt>.
     */
    public RtpChannelTransformEngine getTransformEngine()
    {
        return this.transformEngine;
    }

    /**
     * Updates the {@code MediaStreamTrackReceiver} with the new RTP encoding
     * parameters.
     *
     * @param sources The {@link List} of {@link SourcePacketExtension} that
     * describes the list of sources of this {@code RtpChannel} and that is
     * used as the input in the update of the Sets the {@link Set} of the SSRCs
     * that this {@link RtpChannel} has signaled.
     *
     * @param sourceGroups The {@link List} of
     * {@link SourceGroupPacketExtension} that describes the list of source
     * groups of this {@link RtpChannel}.
     */
    public boolean setRtpEncodingParameters(
        List<SourcePacketExtension> sources,
        List<SourceGroupPacketExtension> sourceGroups)
    {
        boolean hasSources = sources != null && !sources.isEmpty();
        boolean hasGroups = sourceGroups != null && !sourceGroups.isEmpty();
        if (!hasSources && !hasGroups)
        {
            return false;
        }

        this.setSources(sources); // TODO remove and rely on MSTs.
        this.setSourceGroups(sourceGroups); // TODO remove and rely on MSTs.

        MediaStreamTrackReceiver
            mediaStreamTrackReceiver = stream.getMediaStreamTrackReceiver();

        if (mediaStreamTrackReceiver != null)
        {
            MediaStreamTrackImpl[] newTracks
                = MediaStreamTrackFactory.createMediaStreamTracks(
                    mediaStreamTrackReceiver, sources, sourceGroups);

            return mediaStreamTrackReceiver.setMediaStreamTracks(newTracks);
        }
        else
        {
            return false;
        }
    }

    /**
     * @return a string which identifies this {@link RtpChannel} for the
     * purposes of logging (i.e. includes the ID of the channel, the ID of its
     * conference and potentially other information). The string is a
     * comma-separated list of "key=value" pairs.
     */
    @Override
    public String getLoggingId()
    {
        return RtpChannel.getLoggingId(this);
    }

    /**
     * @return a string which identifies a specific {@link RtpChannel} for the
     * purposes of logging (i.e. includes the ID of the channel, the ID of its
     * conference and potentially other information). The string is a
     * comma-separated list of "key=value" pairs.
     * @param rtpChannel The {@link RtpChannel} for which to return a string.
     */
    public static String getLoggingId(RtpChannel rtpChannel)
    {
        String channelId = Channel.getLoggingId(rtpChannel);
        MediaStream stream = rtpChannel == null ? null : rtpChannel.getStream();
        return channelId +
            ",stream=" + (stream == null ? "null" : stream.hashCode());
    }

    /**
     * An exception indicating that the maximum size of something was exceeded.
     */
    private static class SizeExceededException extends Exception
    {}

    /**
     * Holds statistics for an {@link RtpChannel}.
     */
    protected class Statistics
    {
        /**
         * Number of bytes sent. Only updated when the {@link MediaStream} is
         * closed.
         */
        protected long bytesSent = -1;

        /**
         * Number of bytes received. Only updated when the {@link MediaStream}
         * is closed.
         */
        protected long bytesReceived = -1;

        /**
         * Number of packets sent. Only updated when the {@link MediaStream} is
         * closed.
         */
        protected long packetsSent = -1;

        /**
         * Number of packets received. Only updated when the {@link MediaStream}
         * is closed.
         */
        protected long packetsReceived = -1;

        /**
         * Number of bytes retransmitted.
         */
        protected final AtomicLong bytesRetransmitted = new AtomicLong();

        /**
         * Number of bytes for packets which were requested and found in the
         * cache, but were intentionally not retransmitted.
         */
        protected final AtomicLong bytesNotRetransmitted = new AtomicLong();

        /**
         * Number of packets retransmitted.
         */
        protected final AtomicLong packetsRetransmitted = new AtomicLong();

        /**
         * Number of packets which were requested and found in the cache, but
         * were intentionally not retransmitted.
         */
        protected AtomicLong packetsNotRetransmitted = new AtomicLong();

        /**
         * The number of packets for which retransmission was requested, but
         * they were missing from the cache.
         */
        protected AtomicLong packetsMissingFromCache = new AtomicLong();
    }
}
