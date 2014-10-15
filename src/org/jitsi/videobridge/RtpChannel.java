/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;

import javax.media.rtp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.RTPHeader;

import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.zrtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.xmpp.*;

/**
 * Represents channel in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class RtpChannel
    extends Channel
    implements PropertyChangeListener
{
    /**
     * The <tt>Logger</tt> used by the <tt>RtpChannel</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(RtpChannel.class);

    /**
     * An empty array of received synchronization source identifiers (SSRCs).
     * Explicitly defined to reduce allocations and, consequently, the effects
     * of garbage collection.
     */
    private static final long[] NO_RECEIVE_SSRCS = new long[0];

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
     * Holds the <tt>RtpChannelDatagramFilter</tt> instances (if any) used by
     * this channel. The filter for RTP is at index 0, the filter for RTCP at
     * index 1.
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
     * The last known number of lost packets for this channel.
     */
    private long lastKnownPacketsLostNB = 0;

    /**
     * The last known number of packets that are received or sent for this
     * channel.
     */
    private long lastKnownPacketsNB = 0;

    /**
     * The last known number of received bytes.
     */
    private long lastKnownReceivedBytes = 0;

    /**
     * The last known number of sent bytes.
     */
    private long lastKnownSentBytes = 0;

    /**
     * The <tt>PropertyChangeListener</tt> which listens to
     * <tt>PropertyChangeEvent</tt>s.
     */
    private final PropertyChangeListener propertyChangeListener
        = new WeakReferencePropertyChangeListener(this);

    /**
     * Contains the payload type numbers configured for this channel.
     */
    int[] receivePTs = new int[0];

    /**
     * The list of RTP SSRCs received on this <tt>Channel</tt>. An element at
     * an even index represents a SSRC and its consecutive element at an odd
     * index specifies the time in milliseconds when the SSRC was last seen (in
     * order to enable timing out SSRCs).
     */
    long[] receiveSSRCs = NO_RECEIVE_SSRCS;

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
    private final MediaStream stream;

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
     */
    private final SessionAddress streamTarget = new SessionAddress();

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
     * @throws Exception if an error occurs while initializing the new instance
     */
    public RtpChannel(Content content, String id, String channelBundleId)
        throws Exception
    {
        super(content, id, channelBundleId);

        MediaService mediaService = getMediaService();
        MediaType mediaType = getContent().getMediaType();

        stream
            = mediaService.createMediaStream(
                null,
                mediaType,
                getDtlsControl());
        /*
         * Add the PropertyChangeListener to the MediaStream prior to performing
         * further initialization so that we do not miss changes to the values
         * of properties we may be interested in.
         */
        stream.addPropertyChangeListener(streamPropertyChangeListener);
        stream.setName(getID());
        stream.setProperty(RtpChannel.class.getName(), this);

        /*
         * In the case of content mixing, each Channel has its own local
         * synchronization source identifier (SSRC), which Jitsi Videobridge
         * pre-announces.
         */
        initialLocalSSRC = Videobridge.RANDOM.nextInt();

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
                = ctrlAddr.equals(p.getAddress()) && (ctrlPort == p.getPort());
        }

        if (accept)
        {
            // Note that this Channel is still active.
            touch();

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
                = dataAddr.equals(p.getAddress()) && (dataPort == p.getPort());
        }

        if (accept)
        {
            // Note that this Channel is still active.
            touch();

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
                    boolean notify = addReceiveSSRC(ssrc);

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
                                            ssrc & 0xffffffffl,
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
                            MediaFormat format
                                = payloadTypes.get(Byte.valueOf((byte) pt));

                            if ((format != null)
                                    && !format.equals(stream.getFormat()))
                            {
                                stream.setFormat(format);
                                stream.setDirection(MediaDirection.SENDRECV);
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
     * @return <tt>true</tt> if <tt>receiveSSRC</tt> was added to the list
     * (i.e. was not previously there); otherwise, <tt>false</tt>
     */
    private synchronized boolean addReceiveSSRC(int receiveSSRC)
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

        // add
        long[] newReceiveSSRCs = new long[length + 2];

        System.arraycopy(receiveSSRCs, 0, newReceiveSSRCs, 0, length);
        newReceiveSSRCs[length] = 0xFFFFFFFFL & receiveSSRC;
        newReceiveSSRCs[length + 1] = now;
        receiveSSRCs = newReceiveSSRCs;

        return true;
    }

    /**
     * Asks this <tt>Channel</tt> to request keyframes in the RTP video streams
     * that it receives.
     */
    void askForKeyframes()
    {
        int[] receiveSSRCs = getReceiveSSRCs();
        askForKeyframes(receiveSSRCs);
    }

    /**
     * Asks this <tt>Channel</tt> to request keyframes in the RTP video streams
     * that it receives.
     *
     * @param receiveSSRCs the SSRCs to request an FIR for.
     */
    public void askForKeyframes(int[] receiveSSRCs)
    {
        if (receiveSSRCs != null && receiveSSRCs.length != 0)
        {
            RTCPFeedbackMessageSender rtcpFeedbackMessageSender
                = getContent().getRTCPFeedbackMessageSender();

            if (rtcpFeedbackMessageSender != null)
                rtcpFeedbackMessageSender.sendFIR(stream, receiveSSRCs);
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
            stream.setProperty(Channel.class.getName(), null);
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
     *                 to set the values of the properties of this instance.
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;

        /*
         * FIXME The attribute rtp-level-relay-type/Channel property
         * rtpLevelRelayType is pretty much the most important given that Jitsi
         * Videobridge implements an RTP-level relay. Unfortunately, we do not
         * currently support switching between the differents types of RTP-level
         * relays. The following is a hack/workaround making sure that the
         * attribute/property in question has a value as soon as necessary and
         * before this Channel is made available to the conference focus for
         * consumption.
         */
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
    private void dominantSpeakerChanged()
    {
        /*
         * TODO Invoke conferenceSpeechActivity.getDominantEndpoint() and, for
         * example, notify the Jitsi Videobridge client about the dominance
         * switch to a new speaker.
         */
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
     * Returns the number of lost packets for this channel since last time the
     * method is called.
     *
     * Note: {@link org.jitsi.videobridge.stats.VideobridgeStatistics} uses this
     * method and it relies on the fact that the method is not called from
     * anywhere else. This should probably be refactored.
     *
     * @return the number of lost packets since last time the method is called.
     */
    public long getLastPacketsLostNB()
    {
        long newPacketsLost = stream.getMediaStreamStats().getNbPacketsLost();
        long lastPacketsNB = newPacketsLost - lastKnownPacketsLostNB;

        if (lastPacketsNB < 0)
        {
            return 0;
        }
        else
        {
            lastKnownPacketsLostNB = newPacketsLost;
            return lastPacketsNB;
        }
    }

    /**
     * Returns the number of packets that are sent or received for this channel
     * since last time the method is called.
     *
     * Note: {@link org.jitsi.videobridge.stats.VideobridgeStatistics} uses this
     * method and it relies on the fact that the method is not called from
     * anywhere else. This should probably be refactored.
     *
     * @return  the number of packets that are sent or received since last time
     * the method is called.
     */
    public long getLastPacketsNB()
    {
        long newPackets = stream.getMediaStreamStats().getNbPackets();
        long lastPacketsNB = newPackets - lastKnownPacketsNB;

        if (lastPacketsNB < 0)
        {
            return 0;
        }
        else
        {
            lastKnownPacketsNB = newPackets;
            return lastPacketsNB;
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
     * Returns the number of received bytes since the last time the
     * method was called.
     *
     * Note: {@link org.jitsi.videobridge.stats.VideobridgeStatistics} uses this
     * method and it relies on the fact that the method is not called from
     * anywhere else. This should probably be refactored.
     *
     * @return the number of received bytes.
     */
    public long getNBReceivedBytes()
    {
        long bytes = 0;
        long newBytes = stream.getMediaStreamStats().getNbReceivedBytes();
        if(newBytes > lastKnownReceivedBytes)
        {
            bytes += newBytes - lastKnownReceivedBytes;
            lastKnownReceivedBytes = newBytes;
        }

        return bytes;
    }

    /**
     * Returns the number of sent bytes since the last time the
     * method was called.
     *
     * Note: {@link org.jitsi.videobridge.stats.VideobridgeStatistics} uses this
     * method and it relies on the fact that the method is not called from
     * anywhere else. This should probably be refactored.
     *
     * @return the number of sent bytes.
     */
    public long getNBSentBytes()
    {
        long bytes = 0;

        long newBytes = stream.getMediaStreamStats().getNbSentBytes();

        if(newBytes > lastKnownSentBytes)
        {
            bytes += newBytes - lastKnownSentBytes;
            lastKnownSentBytes = newBytes;
        }

        return bytes;
    }

    /**
     * Gets a list of the RTP SSRCs received on this <tt>Channel</tt>.
     *
     * @return an array of <tt>int</tt>s which represents a list of the RTP
     * SSRCs received on this <tt>Channel</tt>
     */
    public synchronized int[] getReceiveSSRCs()
    {
        final int length = this.receiveSSRCs.length;

        if (length == 0)
            return ColibriConferenceIQ.NO_SSRCS;
        else
        {
            int[] receiveSSRCs = new int[length / 2];

            for (int src = 0, dst = 0; src < length; src += 2, dst++)
                receiveSSRCs[dst] = (int) this.receiveSSRCs[src];
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
     * Determines whether a specific <tt>Channel</tt> is within the set of
     * <tt>Channel</tt>s limited by <tt>lastN</tt> i.e. whether the RTP video
     * streams of the specified channel are to be sent to the remote endpoint of
     * this <tt>Channel</tt>.
     *
     * @param channel the <tt>Channel</tt> to be checked whether it is within
     * the set of <tt>Channel<tt>s limited by <tt>lastN</tt> i.e. whether its
     * RTP streams are to be sent to the remote endpoint of this
     * <tt>Channel</tt>
     * @return <tt>true</tt> if the RTP streams of <tt>channel</tt> are to be
     * sent to the remote endpoint of this <tt>Channel</tt>; otherwise,
     * <tt>false</tt>. The implementation of the <tt>RtpChannel</tt> class
     * always returns <tt>true</tt>.
     */
    public boolean isInLastN(Channel channel)
    {
        return true;
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
        // connector
        StreamConnector connector = getStreamConnector();

        if (connector == null)
            return;
        else
            stream.setConnector(connector);

        // target
        MediaStreamTarget streamTarget = createStreamTarget();

        if (streamTarget != null)
        {
            InetSocketAddress dataAddr = streamTarget.getDataAddress();

            if (dataAddr != null)
            {
                this.streamTarget.setDataHostAddress(dataAddr.getAddress());
                this.streamTarget.setDataPort(dataAddr.getPort());
            }

            InetSocketAddress ctrlAddr = streamTarget.getControlAddress();

            if (ctrlAddr != null)
            {
                this.streamTarget.setControlHostAddress(ctrlAddr.getAddress());
                this.streamTarget.setControlPort(ctrlAddr.getPort());
            }

            if (dataAddr != null)
                stream.setTarget(streamTarget);
        }

        Content content = getContent();

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

            stream.start();
        }

        if (logger.isTraceEnabled())
        {
            logger.trace(
                    "Direction of channel " + getID() + " of content "
                        + content.getName() + " of conference "
                        + content.getConference().getID() + " is "
                        + stream.getDirection() + ".");
        }

        touch(); // It seems this Channel is still active.
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
                /*
                 * A telephony conference will still function, albeit with reduced
                 * SSRC-dependent functionality such as audio levels.
                 */
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
            oldValue.removeChannel(this);
        if (newValue != null)
            newValue.addChannel(this);
    }

    /**
     * Notifies this instance that a specific <tt>List</tt> of payload types was
     * set on this instance. Allows extenders to override.
     *
     * @param payloadTypes the <tt>List</tt> of payload types which was set on
     * this instance and which is the cause of the notification
     * @param googleChrome <tt>true</tt> if it looks like Google Chrome (in
     * distinction with Jitsi) is the remote endpoint based on
     * <tt>payloadTypes</tt>; otherwise, <tt>false</tt> 
     */
    protected void payloadTypesSet(
            List<PayloadTypePacketExtension> payloadTypes,
            boolean googleChrome)
    {
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
    private synchronized boolean removeReceiveSSRC(int receiveSSRC)
    {
        final int length = receiveSSRCs.length;
        boolean removed = false;

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

                    removed = true;
                    break;
                }
            }
        }

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
            Channel source)
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
                /*
                 * TODO We will try to recognize Google Chrome (in distinction
                 * with Jitsi) acting as the remote endpoint in order to use
                 * RTPExtension.SSRC_AUDIO_LEVEL_URN instead of
                 * RTPExtension.CSRC_AUDIO_LEVEL_URN while we do not support the
                 * negotiation of RTP header extension IDs.
                 */
                boolean googleChrome = false;

                receivePTs = new int[payloadTypeCount];
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

                    if (mediaFormat == null)
                    {
                        if (!googleChrome
                                && "iSAC".equalsIgnoreCase(
                                        payloadType.getName()))
                        {
                            googleChrome = true;
                        }
                    }
                    else
                    {
                        stream.addDynamicRTPPayloadType(
                                (byte) payloadType.getID(),
                                mediaFormat);
                    }
                }

                payloadTypesSet(payloadTypes, googleChrome);
            }
        }

        touch(); // It seems this Channel is still active.
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
     * <tt>lastN</tt> <tt>Endpoint</tt>s of this <tt>Channel</tt>
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
        String prefix = MediaStreamImpl.class.getName() + ".rtpConnector.";

        if (propertyName.startsWith(prefix))
        {
            Object newValue = ev.getNewValue();

            if (newValue instanceof RTPConnectorInputStream)
            {
                String rtpConnectorPropertyName
                    = propertyName.substring(prefix.length());
                DatagramPacketFilter datagramPacketFilter;

                if (rtpConnectorPropertyName.equals("controlInputStream"))
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
                else if (rtpConnectorPropertyName.equals("dataInputStream"))
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
     * Enables or disables the adaptive lastN functionality.
     *
     * Does nothing, allows extenders to implement.
     *
     * @param adaptiveLastN <tt>true</tt> to enabled and <tt>false</tt> to
     * disable adaptive lastN.
     */
    public void setAdaptiveLastN(boolean adaptiveLastN)
    {}
}
