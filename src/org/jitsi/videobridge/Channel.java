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

import javax.media.rtp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.protocol.*;
import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.RTPHeader;

import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.zrtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.event.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.osgi.framework.*;

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
     * The <tt>Logger</tt> used by the <tt>Channel</tt> class and its instances
     * to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Channel.class);

    /**
     * The default number of seconds of inactivity after which <tt>Channel</tt>s
     * expire.
     */
    public static final int DEFAULT_EXPIRE = 60;

    /**
     * The pool of threads utilized by <tt>Channel</tt> (e.g. to invoke
     * {@link WrapupConnectivityEstablishmentCommand}).
     */
    private static final ExecutorService executorService
        = Executors.newCachedThreadPool();

    /**
     * The name of the <tt>Channel</tt> property which indicates whether the
     * conference focus is the initiator/offerer (as opposed to the
     * responder/answerer) of the media negotiation associated with the
     * <tt>Channel</tt>.
     */
    public static final String INITIATOR_PROPERTY = "initiator";

    private static final long[] NO_RECEIVE_SSRCS = new long[0];

    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level 
     */
    private static void logd(String s)
    {
        /*
         * FIXME Jitsi Videobridge uses the defaults of java.util.logging at the
         * time of this writing but wants to log at debug level at all times for
         * the time being in order to facilitate early development.
         */
        logger.info(s);
    }

    /**
     * The <tt>Content</tt> which has initialized this <tt>Channel</tt>.
     */
    private final Content content;

    /**
     * The <tt>Endpoint</tt> of the conference participant associated with this
     * <tt>Channel</tt>.
     */
    private Endpoint endpoint;

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
     * The local synchronization source identifier (SSRC) to be pre-announced.
     * Currently, the value is taken into account in the case of content mixing
     * and not in the case of RTP translation.
     */
    private final long initialLocalSSRC;

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
     * The <tt>TransportManager</tt> that represents the Jingle transport of
     * this <tt>Channel</tt>.
     */
    private TransportManager transportManager;

    /**
     * The <tt>Object</tt> which synchronizes the access to
     * {@link #transportManager}.
     */
    private final Object transportManagerSyncRoot = new Object();

    /**
     * The <tt>WrapupConnectivityEstablishmentCommand</tt> submitted to
     * {@link #executorService} and not completed yet.
     */
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
     * Initializes a <tt>MediaStreamTarget</tt> instance which identifies the
     * remote addresses to transmit RTP and RTCP to and from.
     *
     * @return a <tt>MediaStreamTarget</tt> instance which identifies the
     * remote addresses to transmit RTP and RTCP to and from
     */
    private MediaStreamTarget createStreamTarget()
    {
        synchronized (transportManagerSyncRoot)
        {
            TransportManager transportManager = this.transportManager;

            return
                (transportManager == null)
                    ? null
                    : transportManager.getStreamTarget();
        }
    }

    /**
     * Initializes a new <tt>TransportManager</tt> instance which has a specific
     * XML namespace.
     *
     * @param xmlNamespace the XML namespace of the new
     * <tt>TransportManager</tt> instance to be initialized
     * @return a new <tt>TransportManager</tt> instance which has the specified
     * <tt>xmlNamespace</tt>
     * @throws IOException if an error occurs during the initialization of the
     * new <tt>TransportManager</tt> instance which has the specified
     * <tt>xmlNamespace</tt>
     */
    private TransportManager createTransportManager(String xmlNamespace)
        throws IOException
    {
        if (IceUdpTransportPacketExtension.NAMESPACE.equals(xmlNamespace))
        {
            return new IceUdpTransportManager(this);
        }
        else if (RawUdpTransportPacketExtension.NAMESPACE.equals(xmlNamespace))
        {
            return new RawUdpTransportManager(this);
        }
        else
        {
            throw new IllegalArgumentException(
                    "Unsupported Jingle transport " + xmlNamespace);
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

        Endpoint endpoint = getEndpoint();

        if (endpoint != null)
            iq.setEndpoint(endpoint.getID());

        iq.setExpire(getExpire());
        iq.setID(getID());
        iq.setInitiator(isInitiator());
        iq.setLastN(lastN);

        long initialLocalSSRC = getInitialLocalSSRC();

        if (initialLocalSSRC != -1)
        {
            SourcePacketExtension source = new SourcePacketExtension();

            source.setSSRC(initialLocalSSRC);
            iq.addSource(source);
        }
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

        Content content = getContent();

        try
        {
            content.expireChannel(this);
        }
        finally
        {
            Conference conference = content.getConference();

            try
            {
                stream.close();
                stream.removePropertyChangeListener(
                        streamPropertyChangeListener);
            }
            catch (Throwable t)
            {
                logger.warn(
                        "Failed to close the MediaStream/stream of channel "
                            + getID() + " of content " + content.getName()
                            + " of conference " + conference.getID() + "!",
                        t);
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
                    logger.warn(
                            "Failed to close the"
                                + " TransportManager/transportManager of"
                                + " channel " + getID() + " of content "
                                + content.getName() + " of conference "
                                + conference.getID() + "!",
                            t);
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                }

                Videobridge videobridge = conference.getVideobridge();

                logd(
                        "Expired channel " + getID() + " of content "
                            + content.getName() + " of conference "
                            + conference.getID()
                            + ". The total number of conferences is now "
                            + videobridge.getConferenceCount() + ", channels "
                            + videobridge.getChannelCount() + ".");
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
     * Gets the <tt>BundleContext</tt> associated with this <tt>Channel</tt>.
     * The method is a convenience which gets the <tt>BundleContext</tt>
     * associated with the XMPP component implementation in which the
     * <tt>Videobridge</tt> associated with this instance is executing.
     *
     * @return the <tt>BundleContext</tt> associated with this <tt>Channel</tt>
     */
    public BundleContext getBundleContext()
    {
        return getContent().getBundleContext();
    }

    /**
     * Gets the <tt>Endpoint</tt> of the conference participant associated with
     * this <tt>Channel</tt>.
     *
     * @return the <tt>Endpoint</tt> of the conference participant associated
     * with this <tt>Channel</tt>
     */
    public Endpoint getEndpoint()
    {
        return endpoint;
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
                wrapupConnectivityEstablishmentCommand = null;
                transportManager
                    = createTransportManager(
                            getContent()
                                .getConference()
                                    .getVideobridge()
                                        .getDefaultTransportManager());

                /*
                 * The implementation of the Jingle Raw UDP transport does not
                 * establish connectivity. 
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
    private void maybeStartStream()
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
        finally
        {
            if (interrupted)
                Thread.currentThread().interrupt();
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
     * Runs in a (pooled) thread associated with a specific
     * <tt>WrapupConnectivityEstablishmentCommand</tt> to invoke
     * {@link TransportManager#wrapupConnectivityEstablishment()} on a specific
     * <tt>TransportManager</tt> and then {@link #maybeStartStream()} on this
     * <tt>Channel</tt> if the <tt>TransportManager</tt> succeeds at producing a
     * <tt>StreamConnector</tt>.
     *
     * @param wrapupConnectivityEstablishmentCommand the
     * <tt>WrapupConnectivityEstablishmentCommand</tt> which is running in a
     * (pooled) thread and which specifies the <tt>TransportManager</tt> on
     * which <tt>TransportManager.wrapupConnectivityEstablishment</tt> is to be
     * invoked.
     */
    private void runInWrapupConnectivityEstablishmentCommand(
            WrapupConnectivityEstablishmentCommand
                wrapupConnectivityEstablishmentCommand)
    {
        TransportManager transportManager
            = wrapupConnectivityEstablishmentCommand.transportManager;

        /*
         * TransportManager.wrapupConnectivityEstablishment() may take a long
         * time to complete. Do not even execute it if it will be of no use to
         * the current state of this Channel.
         */
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
            Content content = getContent();

            logger.error(
                    "Failed to wrapup the connectivity establishment of the"
                        + " TransportManager/transportManager of channel "
                        + getID() + " of content " + content.getName()
                        + " of conference " + content.getConference().getID()
                        + "!",
                    ofe);
            return;
        }

        synchronized (transportManagerSyncRoot)
        {
            /*
             * TransportManager.wrapupConnectivityEstablishment() may have taken
             * a long time to complete. Do not attempt to modify the stream of
             * this Channel if it will be of no use to the current state of this
             * Channel.
             */
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
                Content content = getContent();

                logger.error(
                        "Failed to start the MediaStream/stream of channel "
                            + getID() + " of content " + content.getName()
                            + " of conference "
                            + content.getConference().getID() + "!",
                        ioe);
            }
        }
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
     * Sets the identifier of the endpoint of the conference participant
     * associated with this <tt>Channel</tt>.
     *
     * @param endpoint the identifier of the endpoint of the conference
     * participant associated with this <tt>Channel</tt>
     */
    public void setEndpoint(String endpoint)
    {
        try
        {
            Endpoint oldValue = this.endpoint;

            // Is the endpoint really changing?
            if (oldValue == null)
            {
                if (endpoint == null)
                    return;
            }
            else if (oldValue.getID().equals(endpoint))
            {
                return;
            }

            // The endpoint is really changing.
            Endpoint newValue
                = getContent().getConference().getOrCreateEndpoint(endpoint);

            if (oldValue != newValue)
            {
                if (oldValue != null)
                    oldValue.removeChannel(this);

                this.endpoint = newValue;

                if (newValue != null)
                    newValue.addChannel(this);
            }
        }
        finally
        {
            touch(); // It seems this Channel is still active.
        }
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
        {
            /*
             * We will, of course, fire a PropertyChangeEvent to the outside
             * world. We will first handle the property change inside though.
             */
            SrtpControl srtpControl = stream.getSrtpControl();

            if (srtpControl instanceof DtlsControl)
            {
                DtlsControl dtlsControl = (DtlsControl) srtpControl;

                dtlsControl.setSetup(
                        isInitiator()
                            ? DtlsControl.Setup.PASSIVE
                            : DtlsControl.Setup.ACTIVE);
            }

            firePropertyChange(INITIATOR_PROPERTY, oldValue, newValue);
        }
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
                transportManager = createTransportManager(xmlNamespace);

                Content content = getContent();

                logd(
                        "Set " + transportManager.getClass().getSimpleName()
                            + " #"
                            + Integer.toHexString(transportManager.hashCode())
                            + " on channel " + getID() + " of content "
                            + content.getName() + " of conference "
                            + content.getConference().getID() + ".");
            }
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

    /**
     * Schedules the asynchronous execution of
     * {@link TransportManager#wrapupConnectivityEstablishment()} on a specific
     * <tt>TransportManager</tt> in a (pooled) thread in anticipation of a
     * <tt>StreamConnector</tt> to be set on {@link #stream}.
     *
     * @param transportManager the <tt>TransportManager</tt> on which
     * <tt>TransportManager.wrapupConnectivityEstablishment()</tt> is to be
     * invoked in anticipation of a <tt>StreamConnector</tt> to be set on
     * <tt>stream</tt>.
     */
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

    /**
     * Implements a <tt>Runnable</tt> to be executed in a (pooled) thread in
     * order to invoke
     * {@link TransportManager#wrapupConnectivityEstablishment()} on a specific
     * <tt>TransportManager</tt> and possibly set a <tt>StreamConnector</tt> on
     * and start {@link #stream}.
     *
     * @author Lyubomir Marinov
     */
    private class WrapupConnectivityEstablishmentCommand
        implements Runnable
    {
        /**
         * The <tt>TransportManager</tt> on which this instance is to invoke
         * {@link TransportManager#wrapupConnectivityEstablishment()}.
         */
        public final TransportManager transportManager;

        /**
         * Initializes a new <tt>WrapupConnectivityEstablishmentCommand</tt>
         * which is to invoke
         * {@link TransportManager#wrapupConnectivityEstablishment()} on a
         * specific <tt>TransportManager</tt> and possibly set a
         * <tt>StreamConnector</tt> on and start {@link #stream}.
         *
         * @param transportManager the <tt>TransportManager</tt> on which the
         * new instance is to invoke
         * <tt>TransportManager.wrapupConnectivityEstablishment()</tt> 
         */
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
