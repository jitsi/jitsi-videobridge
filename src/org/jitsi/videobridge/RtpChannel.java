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
import org.jitsi.impl.neomedia.transform.zrtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.event.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.videobridge.xmpp.*;

/**
 * Represents channel in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class RtpChannel
    extends Channel
{
    private static final long[] NO_RECEIVE_SSRCS = new long[0];

    /**
     * The ID of this <tt>Channel</tt> (which is unique within the list of
     * <tt>Channel</tt>s listed in {@link #content} while this instance is
     * listed there as well).
     */
    private final String id;

    /**
     * The local synchronization source identifier (SSRC) to be pre-announced.
     * Currently, the value is taken into account in the case of content mixing
     * and not in the case of RTP translation.
     */
    private final long initialLocalSSRC;

    /**
     * The maximum number of video RTP stream to be sent from Jitsi Videobridge
     * to the endpoint associated with this video <tt>Channel</tt>.
     */
    private Integer lastN;

    /**
     * The list of RTP SSRCs received on this <tt>Channel</tt>. An element at
     * an even index represents a SSRC and its consecutive element at an odd
     * index specifies the time in milliseconds when the SSRC was last seen (in
     * order to enable timing out SSRCs).
     */
    private long[] receiveSSRCs = NO_RECEIVE_SSRCS;

    /**
     * The type of RTP-level relay (in the terms specified by RFC 3550
     * &quot;RTP: A Transport Protocol for Real-Time Applications&quot; in
     * section 2.3 &quot;Mixers and Translators&quot;) used for this
     * <tt>Channel</tt>.
     */
    private RTPLevelRelayType rtpLevelRelayType;

    /**
     * The <tt>SSRCFactory</tt> which is utilized by {@link #stream} to generate
     * new synchronization source (SSRC) identifiers. 
     */
    private final SSRCFactoryImpl ssrcFactory = new SSRCFactoryImpl();

    /**
     * THe <tt>MediaStream</tt> which this <tt>Channel</tt> adapts to the terms
     * of Jitsi Videobridge and which adapts this <tt>Channel</tt> to the terms
     * of <tt>neomedia</tt>.
     */
    private final MediaStream stream;

    /**
     * The <tt>SimpleAudioLevelListener</tt> instance which is set on
     * <tt>AudioMediaStream</tt> via
     * {@link AudioMediaStream#setStreamAudioLevelListener(
     * SimpleAudioLevelListener)} in order to have the audio levels of the
     * contributing sources calculated and to end enable the functionality of
     * {@link #lastN}.
     */
    private SimpleAudioLevelListener streamAudioLevelListener;

    /**
     * The <tt>PropertyChangeListener</tt> which listens to changes of the
     * values of {@link #stream}'s properties.
     */
    private final PropertyChangeListener streamPropertyChangeListener
        = new PropertyChangeListener()
                {
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
     * @throws Exception if an error occurs while initializing the new instance
     */
    public RtpChannel(Content content, String id)
        throws Exception
    {
        super(content);
        if (id == null)
            throw new NullPointerException("id");

        this.id = id;

        MediaService mediaService = getMediaService();
        MediaType mediaType = getContent().getMediaType();

        stream
            = mediaService.createMediaStream(
                    null,
                    mediaType,
                    mediaService.createSrtpControl(SrtpControlType.DTLS_SRTP));
        /*
         * Add the PropertyChangeListener to the MediaStream prior to performing
         * further initialization so that we do not miss changes to the values
         * of properties we may be interested in.
         */
        stream.addPropertyChangeListener(streamPropertyChangeListener);
        stream.setName(this.id);
        stream.setSSRCFactory(ssrcFactory);

        /*
         * Jitsi Videobridge pre-announces the local synchronization source
         * identifier (SSRC) in the case of content mixing and not in the case
         * of RTP translation. Anyway, this distinction will be put in effect at
         * the time the value is queried for the purposes of simplification
         * here.
         */
        initialLocalSSRC = ssrcFactory.doGenerateSSRC() & 0xFFFFFFFFL;

        touch();
    }

    /**
     * Determines whether a specific <tt>DatagramPacket</tt> received on the
     * control <tt>DatagramSocket</tt> of this <tt>Channel</tt> is to be
     * accepted for further processing within Jitsi Videobridge or is to be
     * rejected/dropped. When the first <tt>DatagramPacket</tt> arrives, makes
     * its source the only acceptable one for the control
     * <tt>DatagramSocket</tt> and uses it as the control target of
     * {@link #rtpConnector}.
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
     * and uses it as the data target of {@link #rtpConnector}.
     *
     * @param p the <tt>DatagramPacket</tt> received on the data
     * <tt>DatagramSocket</tt> of this <tt>Channel</tt>
     * @return <tt>true</tt> if the specified <tt>DatagramPacket</tt> is to be
     * accepted for further processing within Jitsi Videobridge or
     * <tt>false</tt> to reject/drop it
     */
    private boolean acceptDataInputStreamDatagramPacket(DatagramPacket p)
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
     * {@inheritDoc}
     */
    @Override
    protected void closeStream()
    {
        stream.close();
        stream.removePropertyChangeListener(
            streamPropertyChangeListener);
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
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        super.describe(commonIq);

        ColibriConferenceIQ.Channel iq
            = (ColibriConferenceIQ.Channel) commonIq;

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

        iq.setDirection(stream.getDirection());

        iq.setID(getID());
        iq.setLastN(lastN);

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
     * {@inheritDoc}
     */
    @Override
    protected DtlsControl getDtlsControl()
    {
        SrtpControl srtpControl = stream.getSrtpControl();

        if(srtpControl instanceof DtlsControl)
        {
            return (DtlsControl) srtpControl;
        }
        else
        {
            return null;
        }
    }

    /**
     * Gets the ID of this <tt>Channel</tt> (which is unique within the list of
     * <tt>Channel</tt> listed in {@link #content} while this instance is listed
     * there as well).
     *
     * @return the ID of this <tt>Channel</tt> (which is unique within the list
     * of <tt>Channel</tt> listed in {@link #content} while this instance is
     * listed there as well)
     */
    public final String getID()
    {
        return id;
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
        return
            RTPLevelRelayType.MIXER.equals(getRTPLevelRelayType())
                ? initialLocalSSRC
                : -1;
    }

    /**
     * Returns a <tt>MediaService</tt> implementation (if any).
     *
     * @return a <tt>MediaService</tt> implementation (if any)
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
     * Gets the <tt>SimpleAudioLevelListener</tt> instance which is set on
     * <tt>AudioMediaStream</tt> via
     * {@link AudioMediaStream#setStreamAudioLevelListener(
     * SimpleAudioLevelListener)} in order to have the audio levels of the
     * contributing sources calculated and to enable the functionality of
     * {@link #lastN}.
     *
     * @return the <tt>SimpleAudioLevelListener</tt> instance
     */
    private SimpleAudioLevelListener getStreamAudioLevelListener()
    {
        if (streamAudioLevelListener == null)
        {
            streamAudioLevelListener
                = new SimpleAudioLevelListener()
                        {
                            public void audioLevelChanged(int level)
                            {
                                streamAudioLevelChanged(level);
                            }
                        };
        }
        return streamAudioLevelListener;
    }

    /**
     * Starts {@link #stream} if it has not been started yet and if the state of
     * this <tt>Channel</tt> meets the prerequisites to invoke
     * {@link MediaStream#start()}. For example, <tt>MediaStream</tt> may be
     * started only after a <tt>StreamConnector</tt> has been set on it and this
     * <tt>Channel</tt> may be able to provide a <tt>StreamConnector</tt> only
     * after {@link #wrapupConnectivityEstablishment(TransportManager)} has
     * completed on {@link #transportManager}.
     *
     * @throws IOException if anything goes wrong while starting <tt>stream</tt>
     */
    @Override
    protected void maybeStartStream()
        throws IOException
    {
        // connector
        StreamConnector connector = createStreamConnector();

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
             * Start the SrtpControl of the MediaStream. As far as our
             * experience with Jitsi goes, the SrtpControl is started prior to
             * the MediaStream there.
             */
            SrtpControl srtpControl = stream.getSrtpControl();

            if (srtpControl != null)
            {
                if (srtpControl instanceof DtlsControl)
                {
                    DtlsControl dtlsControl = (DtlsControl) srtpControl;

                    dtlsControl.setSetup(
                            isInitiator()
                                ? DtlsControl.Setup.PASSIVE
                                : DtlsControl.Setup.ACTIVE);
                }
                srtpControl.start(content.getMediaType());
            }

            stream.start();
        }

        logd(
                "Direction of channel " + getID() + " of content "
                    + content.getName() + " of conference "
                    + content.getConference().getID() + " is "
                    + stream.getDirection() + ".");

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
        if (oldValue != null)
            oldValue.removeChannel(this);

        if (newValue != null)
            newValue.addChannel(this);
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
        this.lastN = lastN;

        touch(); // It seems this Channel is still active.
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
                /*
                 * TODO We will try to recognize Google Chrome (in distinction
                 * with Jitsi) acting as the remote endpoint in order to use
                 * RTPExtension.SSRC_AUDIO_LEVEL_URN instead of
                 * RTPExtension.CSRC_AUDIO_LEVEL_URN while we do not support the
                 * negotiation of RTP header extension IDs.
                 */
                boolean googleChrome = false;

                for (PayloadTypePacketExtension payloadType : payloadTypes)
                {
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

                if (googleChrome && (stream instanceof AudioMediaStream))
                {
                    /*
                     * TODO Use RTPExtension.SSRC_AUDIO_LEVEL_URN instead of
                     * RTPExtension.CSRC_AUDIO_LEVEL_URN while we do not support
                     * the negotiation of RTP header extension IDs.
                     */
                    URI uri;

                    try
                    {
                        uri = new URI(RTPExtension.SSRC_AUDIO_LEVEL_URN);
                    }
                    catch (URISyntaxException e)
                    {
                        uri = null;
                    }
                    if (uri != null)
                        stream.addRTPExtension((byte) 1, new RTPExtension(uri));
                }
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
            this.rtpLevelRelayType = rtpLevelRelayType;

            /*
             * If the RTP-level relay to be used for this Channel is a mixer,
             * then the stream will have a MediaDevice and will not have an
             * RTPTranslator. If the RTP-level relay to be used for this Channel
             * is a translator, then the stream will not have a MediaDevice and
             * will have an RTPTranslator.
             */
            switch (getRTPLevelRelayType())
            {
            case MIXER:
                Content content = getContent();
                MediaDevice device = content.getMixer();

                stream.setDevice(device);

                if (MediaType.AUDIO.equals(content.getMediaType()))
                {
                    /*
                     * Allow the Jitsi Videobridge server to send the audio
                     * levels of the contributing sources to the telephony
                     * conference participants.
                     */
                    List<RTPExtension> rtpExtensions
                        = device.getSupportedExtensions();

                    if (rtpExtensions.size() == 1)
                        stream.addRTPExtension((byte) 1, rtpExtensions.get(0));

                    ((AudioMediaStream) stream).setStreamAudioLevelListener(
                            getStreamAudioLevelListener());
                }

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
     * Generates a new synchronization source (SSRC) identifier.
     *
     * @param cause
     * @param i the number of times that the method has been executed prior to
     * the current invocation
     * @return a randomly chosen <tt>int</tt> value which is to be utilized as a
     * new synchronization source (SSRC) identifier should it be found to be
     * globally unique within the associated RTP session or
     * <tt>Long.MAX_VALUE</tt> to cancel the operation
     */
    private long ssrcFactoryGenerateSSRC(String cause, int i)
    {
        if (initialLocalSSRC != -1)
        {
            if (i == 0)
                return (int) initialLocalSSRC;
            else if (cause.equals(GenerateSSRCCause.REMOVE_SEND_STREAM.name()))
                return Long.MAX_VALUE;
        }
        return ssrcFactory.doGenerateSSRC();
    }

    /**
     * Notifies this instance about a change in the audio level of the (remote)
     * endpoint/conference participant associated with this <tt>Channel</tt>.
     *
     * @param level the new/current audio level of the (remote)
     * endpoint/conference participant associated with this <tt>Channel</tt>
     */
    private void streamAudioLevelChanged(int level)
    {
        /*
         * Whatever, the Jitsi Videobridge is not interested in the audio
         * levels. However, an existing/non-null streamAudioLevelListener has to
         * be set on an AudioMediaStream in order to have the audio levels of
         * the contributing sources calculated at all.
         */
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
                            public boolean accept(DatagramPacket p)
                            {
                                return acceptDataInputStreamDatagramPacket(p);
                            }
                        };
                }
                else
                    datagramPacketFilter = null;

                if (datagramPacketFilter != null)
                {
                    ((RTPConnectorInputStream) newValue)
                        .addDatagramPacketFilter(datagramPacketFilter);
                }
            }
        }
    }

    private class SSRCFactoryImpl
        implements SSRCFactory
    {
        private int i = 0;

        /**
         * The <tt>Random</tt> instance used by this <tt>SSRCFactory</tt> to
         * generate new synchronization source (SSRC) identifiers.
         */
        private final Random random = new Random();

        public int doGenerateSSRC()
        {
            return random.nextInt();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public long generateSSRC(String cause)
        {
            return ssrcFactoryGenerateSSRC(cause, i++);
        }
    }

}
