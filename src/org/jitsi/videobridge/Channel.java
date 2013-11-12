/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.beans.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import javax.media.*;
import javax.media.rtp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.protocol.*;

import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.format.*;
import org.jitsi.impl.neomedia.transform.zrtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.event.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.util.event.*;

/**
 * Represents channel in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class Channel
    extends PropertyChangeNotifier
{
    /**
     * The default number of seconds of inactivity after which <tt>Channel</tt>s
     * expire.
     */
    public static final int DEFAULT_EXPIRE = 60;

    private static final ExecutorService executorService
        = Executors.newCachedThreadPool();

    /**
     * The name of the <tt>Channel</tt> property which indicates whether the
     * conference focus is the initiator/offerer (as opposed to the
     * responder/answerer) of the media negotiation associated with the
     * <tt>Channel</tt>.
     */
    public static final String INITIATOR_PROPERTY = "initiator";

    /**
     * A (dumb) <tt>SimpleAudioLevelListener</tt> instance which is to be set on
     * <tt>AudioMediaStream</tt> via
     * {@link AudioMediaStream#setStreamAudioLevelListener(
     * SimpleAudioLevelListener)} in order to have the audio levels of the
     * contributing sources calculated at all.
     */
    private static SimpleAudioLevelListener streamAudioLevelListener;

    /**
     * The <tt>Content</tt> which has initialized this <tt>Channel</tt>.
     */
    private final Content content;

    /**
     * The number of seconds of inactivity after which this <tt>Channel</tt>
     * expires.
     */
    private int expire = DEFAULT_EXPIRE;

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Channel</tt>.
     */
    private boolean expired = false;

    /**
     * The ID of this <tt>Channel</tt> (which is unique within the list of
     * <tt>Channel</tt>s listed in {@link #content} while this instance is
     * listed there as well).
     */
    private final String id;

    /**
     * The indicator which determines whether the conference focus is the
     * initiator/offerer (as opposed to the responder/answerer) of the media
     * negotiation associated with this instance.
     */
    private boolean initiator = true;

    /**
     * The time in milliseconds of the last activity related to this
     * <tt>Channel</tt>. In the time interval between the last activity and now,
     * this <tt>Channel</tt> is considered inactive.
     */
    private long lastActivityTime;

    /**
     * The list of RTP SSRCs received on this <tt>Channel</tt>. An elements at
     * an even index represents a SSRC and its consecutive element at an odd
     * index specifies the time in milliseconds when the SSRC was last seen (in
     * order to enable timing out SSRCs).
     */
    private long[] receiveSSRCs = ColibriConferenceIQ.NO_SSRCS;

    /**
     * The type of RTP-level relay (in the terms specified by RFC 3550 "RTP: A
     * Transport Protocol for Real-Time Applications" in section 2.3 "Mixers and
     * Translators") used for this <tt>Channel</tt>.
     */
    private final RTPLevelRelayType rtpLevelRelayType;

    /**
     * THe <tt>MediaStream</tt> which this <tt>Channel</tt> adapts to the terms
     * of Jitsi Videobridge and which adapts this <tt>Channel</tt> to the terms
     * of <tt>neomedia</tt>.
     */
    private final MediaStream stream;

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
     * The <tt>TransportManager</tt> that represents the Jingle transport of
     * this <tt>Channel</tt>.
     */
    private TransportManager transportManager;

    /**
     * The <tt>Object</tt> which synchronizes the access to
     * {@link #transportManager}.
     */
    private final Object transportManagerSyncRoot = new Object();

    private
        WrapupConnectivityEstablishmentCommand
            wrapupConnectivityEstablishmentCommand;

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
    public Channel(Content content, String id)
        throws Exception
    {
        if (content == null)
            throw new NullPointerException("content");
        if (id == null)
            throw new NullPointerException("id");

        this.content = content;
        this.id = id;

        MediaType mediaType = this.content.getMediaType();

        rtpLevelRelayType
            = MediaType.AUDIO.equals(mediaType)
                ? RTPLevelRelayType.MIXER
                : RTPLevelRelayType.TRANSLATOR;

        MediaService mediaService = getMediaService();

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

        /*
         * If the RTP-level relay to be used for this Channel is a mixer, then
         * the stream will have a MediaDevice and will not have an
         * RTPTranslator. If the RTP-level relay to be used for this Channel is
         * a translator, then the stream will not have a MediaDevice and will
         * have an RTPTranslator.
         */
        switch (getRTPLevelRelayType())
        {
        case MIXER:
            MediaDevice device = this.content.getMixer();

            stream.setDevice(device);

            if (MediaType.AUDIO.equals(mediaType))
            {
                /*
                 * Allow the Jitsi Videobridge server to send the audio levels
                 * of the contributing sources to the telephony conference
                 * participants .
                 */
                List<RTPExtension> rtpExtensions
                    = device.getSupportedExtensions();

                if (rtpExtensions.size() == 1)
                    stream.addRTPExtension((byte) 1, rtpExtensions.get(0));

                ((AudioMediaStream) stream).setStreamAudioLevelListener(
                        getStreamAudioLevelListener());
            }

            /*
             * It is necessary to start receiving media in order to determine
             * the MediaFormat in which the stream will send the media it
             * generates.
             */
            stream.setDirection(MediaDirection.RECVONLY);
            break;
        case TRANSLATOR:
            stream.setRTPTranslator(this.content.getRTPTranslator());
            break;
        default:
            throw new IllegalStateException("rtpLevelRelayType");
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
            if (p.getLength() > 8)
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
                        long ssrc = RTPTranslatorImpl.readInt(data, offset + 4);

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
            if (p.getLength() >= 12)
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
                    long ssrc = RTPTranslatorImpl.readInt(data, off + 8);

                    boolean notify = false;
                    notify |= addReceiveSSRC(ssrc);

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
     *
     * @return <tt>true</tt> if <tt>receiveSSRC</tt> was added to the list
     * (was not previously there), and <tt>false</tt> if it was already in the
     * list.
     */
    private synchronized boolean addReceiveSSRC(long receiveSSRC)
    {
        long now = System.currentTimeMillis();

        // contains
        final int length = receiveSSRCs.length;

        for (int i = 0; i < length; i += 2)
        {
            if (receiveSSRCs[i] == receiveSSRC)
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
        newReceiveSSRCs[length] = receiveSSRC;
        newReceiveSSRCs[length + 1] = now;
        receiveSSRCs = newReceiveSSRCs;

        return true;
    }

    /**
     * Initializes the pair of <tt>DatagramSocket</tt>s for RTP and RTCP traffic
     * {@link #rtpConnector} is to use.
     *
     * @return a new <tt>StreamConnector</tt> instance which represents the pair
     * of <tt>DatagramSocket</tt>s for RTP and RTCP traffic
     * <tt>rtpConnector</tt> is to use
     * @throws IOException if anything goes wrong while initializing the pair of
     * <tt>DatagramSocket</tt>s for RTP and RTCP traffic <tt>rtpConnector</tt>
     * is to use
     */
    private StreamConnector createStreamConnector()
        throws IOException
    {
        synchronized (transportManagerSyncRoot)
        {
            TransportManager transportManager = this.transportManager;

            return
                (transportManager == null)
                    ? null
                    : transportManager.getStreamConnector();
        }
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.Channel</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     *
     * @param iq the <tt>ColibriConferenceIQ.Channel</tt> on which to set the
     * values of the properties of this instance
     */
    public void describe(ColibriConferenceIQ.Channel iq)
    {
        iq.setDirection(stream.getDirection());
        iq.setExpire(getExpire());
        iq.setID(getID());
        iq.setInitiator(isInitiator());
        iq.setSSRCs(getReceiveSSRCs());

        describeTransportManager(iq);
        describeSrtpControl(iq);
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.Channel</tt> to the values of the respective
     * properties of the <tt>SrtpControl</tT> of the <tt>MediaStream</tt> of
     * this instance.
     * 
     * @param iq the <tt>ColibriConferenceIQ.Channel</tt> on which to set the
     * values of the properties of the <tt>SrtpControl</tt> of the
     * <tt>MediaStream</tt> of this instance
     */
    private void describeSrtpControl(ColibriConferenceIQ.Channel iq)
    {
        SrtpControl srtpControl = stream.getSrtpControl();

        if (srtpControl instanceof DtlsControl)
        {
            DtlsControl dtlsControl = (DtlsControl) srtpControl;
            String fingerprint = dtlsControl.getLocalFingerprint();
            String hash = dtlsControl.getLocalFingerprintHashFunction();

            IceUdpTransportPacketExtension transportPE = iq.getTransport();

            if (transportPE == null)
            {
                transportPE = new RawUdpTransportPacketExtension();
                iq.setTransport(transportPE);
            }

            DtlsFingerprintPacketExtension fingerprintPE
                = transportPE.getFirstChildOfType(
                        DtlsFingerprintPacketExtension.class);

            if (fingerprintPE == null)
            {
                fingerprintPE = new DtlsFingerprintPacketExtension();
                transportPE.addChildExtension(fingerprintPE);
            }
            fingerprintPE.setFingerprint(fingerprint);
            fingerprintPE.setHash(hash);
        }
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.Channel</tt> to the values of the respective
     * properties of {@link #transportManager}.
     * 
     * @param iq the <tt>ColibriConferenceIQ.Channel</tt> on which to set the
     * values of the properties of <tt>transportManager</tt>
     */
    private void describeTransportManager(ColibriConferenceIQ.Channel iq)
    {
        TransportManager transportManager;

        try
        {
            transportManager = getTransportManager();
        }
        catch (IOException ioe)
        {
            throw new UndeclaredThrowableException(ioe);
        }
        if (transportManager != null)
            transportManager.describe(iq);
    }

    /**
     * Expires this <tt>Channel</tt>. Releases the resources acquired by this
     * instance throughout its life time and prepares it to be garbage
     * collected.
     */
    public void expire()
    {
        synchronized (this)
        {
            if (expired)
                return;
            else
                expired = true;
        }

        try
        {
            getContent().expireChannel(this);
        }
        finally
        {
            try
            {
                stream.close();
                stream.removePropertyChangeListener(
                        streamPropertyChangeListener);
            }
            catch (Throwable t)
            {
                t.printStackTrace();
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }
            finally
            {
                try
                {
                    synchronized (transportManagerSyncRoot)
                    {
                        wrapupConnectivityEstablishmentCommand = null;
                        if (transportManager != null)
                            transportManager.close();
                    }
                }
                catch (Throwable t)
                {
                    t.printStackTrace();
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                }
            }
        }
    }

    /**
     * Gets the <tt>Content</tt> which has initialized this <tt>Channel</tt>.
     *
     * @return the <tt>Content</tt> which has initialized this <tt>Content</tt>
     */
    public final Content getContent()
    {
        return content;
    }

    /**
     * Gets the number of seconds of inactivity after which this
     * <tt>Channel</tt> expires.
     *
     * @return the number of seconds of inactivity after which this
     * <tt>Channel</tt> expires
     */
    public int getExpire()
    {
        return expire;
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
     * Gets the time in milliseconds of the last activity related to this
     * <tt>Channel</tt>.
     *
     * @return the time in milliseconds of the last activity related to this
     * <tt>Channel</tt>
     */
    public long getLastActivityTime()
    {
        synchronized (this)
        {
            return lastActivityTime;
        }
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
     * @return an array of <tt>long</tt>s which represents a list of the RTP
     * SSRCs received on this <tt>Channel</tt>
     */
    public synchronized long[] getReceiveSSRCs()
    {
        final int length = this.receiveSSRCs.length;

        if (length == 0)
            return ColibriConferenceIQ.NO_SSRCS;
        else
        {
            long[] receiveSSRCs = new long[length / 2];

            for (int src = 0, dst = 0; src < length; src += 2, dst++)
                receiveSSRCs[dst] = this.receiveSSRCs[src];
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
        return rtpLevelRelayType;
    }

    /**
     * Gets a (dumb) <tt>SimpleAudioLevelListener</tt> instance which is to be
     * set on <tt>AudioMediaStream</tt> via
     * {@link AudioMediaStream#setStreamAudioLevelListener(
     * SimpleAudioLevelListener)} in order to have the audio levels of the
     * contributing sources calculated at all.
     *
     * @return a (dumb) <tt>SimpleAudioLevelListener</tt> instance
     */
    private static SimpleAudioLevelListener getStreamAudioLevelListener()
    {
        if (streamAudioLevelListener == null)
        {
            streamAudioLevelListener
                = new SimpleAudioLevelListener()
                {
                    public void audioLevelChanged(int level)
                    {
                        /*
                         * Whatever, the Jitsi Videobridge is not interested in
                         * the audio levels. However, an existing/non-null
                         * streamAudioLevelListener has to be set on an
                         * AudioMediaStream in order to have the audio levels of
                         * the contributing sources calculated at all.
                         */
                    }
                };
        }
        return streamAudioLevelListener;
    }

    /**
     * Gets the <tt>TransportManager</tt> which represents the Jingle transport
     * of this <tt>Channel</tt>. If the <tt>TransportManager</tt> has not been
     * created yet, it is created.
     *
     * @return the <tt>TransportManager</tt> which represents the Jingle
     * transport of this <tt>Channel</tt>
     */
    private TransportManager getTransportManager()
        throws IOException
    {
        synchronized (transportManagerSyncRoot)
        {
            if (transportManager == null)
            {
                transportManager = new IceUdpTransportManager(this);
                wrapupConnectivityEstablishmentCommand = null;

                /*
                 * XXX The implementation of the Jingle Raw UDP transport does
                 * not establish connectivity so maybeStartStream() is invoked
                 * bellow in case we decide to change the default
                 * TransportManager in the future. 
                 */
                if (RawUdpTransportPacketExtension.NAMESPACE.equals(
                        transportManager.getXmlNamespace()))
                {
                    maybeStartStream();
                }
            }
            return transportManager;
        }
    }

    /**
     * Gets the indicator which determines whether {@link #expire()} has been
     * called on this <tt>Channel</tt>.
     *
     * @return <tt>true</tt> if <tt>expire()</tt> has been called on this
     * <tt>Channel</tt>; otherwise, <tt>false</tt>
     */
    public boolean isExpired()
    {
        synchronized (this)
        {
            return expired;
        }
    }

    /**
     * Gets the indicator which determines whether the conference focus is the
     * initiator/offerer (as opposed to the responder/answerer) of the media
     * negotiation associated with this instance.
     *
     * @return <tt>true</tt> if the conference focus is the initiator/offerer
     * (as opposed to the responder/answerer) of the media negotiation
     * associated with this instance; otherwise, <tt>false</tt>
     */
    public boolean isInitiator()
    {
        return initiator;
    }

    private void maybeStartStream()
        throws IOException
    {
        StreamConnector connector = createStreamConnector();

        if (connector == null)
            return;
        else
            stream.setConnector(connector);

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

                    /*
                     * It makes sense to always start the DTLS-SRTP endpoint
                     * represented by this Channel as a server because Jitsi
                     * Videobridge is a server-side endpoint and thus is
                     * supposed to have a public IP. 
                     */
                    dtlsControl.setDtlsProtocol(
                            DtlsControl.DTLS_SERVER_PROTOCOL);
                }
                srtpControl.start(getContent().getMediaType());
            }

            stream.start();
        }

        touch(); // It seems this Channel is still active.
    }

    /**
     * Notifies the focus of this <tt>Channel</tt>'s <tt>Conference</tt>
     * of the current state of this <tt>Channel</tt>.
     */
    private void notifyFocus()
    {
        boolean interrupted = false;

        try
        {
            Content content = getContent();
            Conference conference = content.getConference();
            ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();

            conference.describe(conferenceIQ);

            ColibriConferenceIQ.Content contentIQ
                = conferenceIQ.getOrCreateContent(content.getName());
            ColibriConferenceIQ.Channel channelIQ
                = new ColibriConferenceIQ.Channel();

            describe(channelIQ);
            contentIQ.addChannel(channelIQ);

            conferenceIQ.setTo(conference.getFocus());
            conferenceIQ.setType(org.jivesoftware.smack.packet.IQ.Type.SET);

            conference.getVideobridge().getComponent().send(conferenceIQ);
        }
        catch (Throwable t)
        {
            /*
             * A telephony conference will still function, albeit with reduced
             * SSRC-dependent functionality such as audio levels.
             */
            if (t instanceof InterruptedException)
                interrupted = true;
            else if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Removes a specific RTP SSRC from the list of SSRCs received on this
     * <tt>Channel</tt>.
     *
     * @param receiveSSRC the RTP SSRC to be removed from the list of SSRCs
     * received on this <tt>Channel</tt>
     *
     * @return <tt>true</tt> if <tt>receiveSSRC</tt> was found in the list of
     * SSRCs received on this <tt>Channel</tt> and removed. And <tt>false</tt>
     * if <tt>receiveSSRC</tt> was not found in the list.
     */
    private synchronized boolean removeReceiveSSRC(long receiveSSRC)
    {
        final int length = receiveSSRCs.length;
        boolean removed = false;

        if (length == 2)
        {
            if (receiveSSRCs[0] == receiveSSRC)
            {
                receiveSSRCs = ColibriConferenceIQ.NO_SSRCS;
                removed = true;
            }
        }
        else
        {
            for (int i = 0; i < length; i += 2)
            {
                if (receiveSSRCs[i] == receiveSSRC)
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

    private void runInWrapupConnectivityEstablishmentCommand(
            WrapupConnectivityEstablishmentCommand
                wrapupConnectivityEstablishmentCommand)
    {
        TransportManager transportManager
            = wrapupConnectivityEstablishmentCommand.transportManager;

        synchronized (transportManagerSyncRoot)
        {
            if (transportManager != this.transportManager)
                return;
            if (wrapupConnectivityEstablishmentCommand
                    != this.wrapupConnectivityEstablishmentCommand)
            {
                return;
            }
            if (isExpired())
                return;
        }

        try
        {
            transportManager.wrapupConnectivityEstablishment();
        }
        catch (OperationFailedException ofe)
        {
            ofe.printStackTrace();
            return;
        }

        synchronized (transportManagerSyncRoot)
        {
            if (transportManager != this.transportManager)
                return;
            if (wrapupConnectivityEstablishmentCommand
                    != this.wrapupConnectivityEstablishmentCommand)
            {
                return;
            }
            if (isExpired())
                return;

            try
            {
                maybeStartStream();
            }
            catch (IOException ioe)
            {
                ioe.printStackTrace();
            }
        }
    }

    /**
     * Sets the direction of the <tt>MediaStream</tt> of this <tt>Channel</tt>.
     * <p>
     * <b>Note</b>: The method does nothing if latching has not finished.
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
     * Sets the number of seconds of inactivity after which this
     * <tt>Channel</tt> is to expire.
     *
     * @param expire the number of seconds of inactivity after which this
     * <tt>Channel</tt> is to expire
     * @throws IllegalArgumentException if <tt>expire</tt> is negative
     */
    public void setExpire(int expire)
    {
        if (expire < 0)
            throw new IllegalArgumentException("expire");

        this.expire = expire;

        if (this.expire == 0)
            expire();
        else
            touch(); // It seems this Channel is still active.
    }

    /**
     * Sets the indicator which determines whether the conference focus is the
     * initiator/offerer (as opposed to the responder/answerer) of the media
     * negotiation associated with this instance.
     *
     * @param initiator <tt>true</tt> if the conference focus is the
     * initiator/offerer (as opposed to the responder/answerer) of the media
     * negotiation associated with this instance; otherwise, <tt>false</tt>
     */
    public void setInitiator(boolean initiator)
    {
        boolean oldValue = this.initiator;

        this.initiator = initiator;

        boolean newValue = this.initiator;

        touch(); // It seems this Channel is still active.

        if (oldValue != newValue)
            firePropertyChange(INITIATOR_PROPERTY, oldValue, newValue);
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
        List<Format> formats = new ArrayList<Format>();

        if ((payloadTypes != null) && (payloadTypes.size() > 0))
        {
            MediaService mediaService = getMediaService();

            if (mediaService != null)
            {
                for (PayloadTypePacketExtension payloadType : payloadTypes)
                {
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

                        @SuppressWarnings("unchecked")
                        Format format
                            = ((MediaFormatImpl<? extends Format>)
                                    mediaFormat)
                                .getFormat();

                        formats.add(format);
                    }
                }
            }
        }

        touch(); // It seems this Channel is still active.
    }

    /**
     * Sets a specific <tt>IceUdpTransportPacketExtension</tt> on this
     * <tt>Channel</tt>.
     *
     * @param transport the <tt>IceUdpTransportPacketExtension</tt> to be set on
     * this <tt>Channel</tt>
     */
    public void setTransport(IceUdpTransportPacketExtension transport)
        throws IOException
    {
        if (transport != null)
        {
            setTransportManager(transport.getNamespace());

            // DTLS-SRTP
            SrtpControl srtpControl = stream.getSrtpControl();

            if (srtpControl instanceof DtlsControl)
            {
                List<DtlsFingerprintPacketExtension> dfpes
                    = transport.getChildExtensionsOfType(
                            DtlsFingerprintPacketExtension.class);

                if (!dfpes.isEmpty())
                {
                    Map<String,String> remoteFingerprints
                        = new LinkedHashMap<String,String>();

                    for (DtlsFingerprintPacketExtension dfpe : dfpes)
                    {
                        remoteFingerprints.put(
                                dfpe.getHash(),
                                dfpe.getFingerprint());
                    }

                    DtlsControl dtlsControl = (DtlsControl) srtpControl;

                    dtlsControl.setRemoteFingerprints(remoteFingerprints);
                }
            }

            TransportManager transportManager = getTransportManager();

            if (transportManager != null)
            {
                if (transportManager.startConnectivityEstablishment(transport))
                    wrapupConnectivityEstablishment(transportManager);
                else
                    maybeStartStream();
            }
        }

        touch(); // It seems this Channel is still active.
    }

    /**
     * Sets the XML namespace of the Jingle transport of this <tt>Channel</tt>.
     * If {@link #transportManager} is non-<tt>null</tt> and does not have the
     * specified <tt>xmlNamespace</tt>, it is closed and replaced with a new
     * <tt>TransportManager</tt> instance which has the specified
     * <tt>xmlNamespace</tt>. If <tt>transportManager</tt> is non-<tt>null</tt>
     * and has the specified <tt>xmlNamespace</tt>, the method does nothing.
     *
     * @param xmlNamespace the XML namespace of the Jingle transport to be set
     * on this <tt>Channel</tt>
     * @throws IOException
     */
    private void setTransportManager(String xmlNamespace)
        throws IOException
    {
        synchronized (transportManagerSyncRoot)
        {
            if ((transportManager != null)
                    && !transportManager.getXmlNamespace().equals(xmlNamespace))
            {
                wrapupConnectivityEstablishmentCommand = null;
                transportManager.close();
                transportManager = null;
            }

            if (transportManager == null)
            {
                wrapupConnectivityEstablishmentCommand = null;

                if (IceUdpTransportPacketExtension.NAMESPACE.equals(
                        xmlNamespace))
                {
                    transportManager = new IceUdpTransportManager(this);
                }
                else if (RawUdpTransportPacketExtension.NAMESPACE.equals(
                        xmlNamespace))
                {
                    transportManager = new RawUdpTransportManager(this);
                }
                else
                {
                    throw new IllegalArgumentException(
                            "Unsupported Jingle transport " + xmlNamespace);
                }
            }
        }

        touch(); // It seems this Channel is still active.
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

    /**
     * Sets the time in milliseconds of the last activity related to this
     * <tt>Channel</tt> to the current system time.
     */
    public void touch()
    {
        long now = System.currentTimeMillis();

        synchronized (this)
        {
            if (getLastActivityTime() < now)
                lastActivityTime = now;
        }
    }

    private void wrapupConnectivityEstablishment(
            TransportManager transportManager)
    {
        synchronized (transportManagerSyncRoot)
        {
            if (transportManager != this.transportManager)
                return;

            if ((wrapupConnectivityEstablishmentCommand != null)
                    && (wrapupConnectivityEstablishmentCommand.transportManager
                            != transportManager))
            {
                wrapupConnectivityEstablishmentCommand = null;
            }
            if (wrapupConnectivityEstablishmentCommand == null)
            {
                wrapupConnectivityEstablishmentCommand
                    = new WrapupConnectivityEstablishmentCommand(
                            transportManager);

                boolean execute = false;

                try
                {
                    executorService.execute(
                            wrapupConnectivityEstablishmentCommand);
                    execute = true;
                }
                finally
                {
                    if (!execute)
                        wrapupConnectivityEstablishmentCommand = null;
                }
            }
        }
    }

    private class WrapupConnectivityEstablishmentCommand
        implements Runnable
    {
        public final TransportManager transportManager;

        public WrapupConnectivityEstablishmentCommand(
                TransportManager transportManager)
        {
            this.transportManager = transportManager;
        }

        @Override
        public void run()
        {
            try
            {
                runInWrapupConnectivityEstablishmentCommand(this);
            }
            finally
            {
                synchronized (transportManagerSyncRoot)
                {
                    if (wrapupConnectivityEstablishmentCommand == this)
                        wrapupConnectivityEstablishmentCommand = null;
                }
            }
        }
    }
}
