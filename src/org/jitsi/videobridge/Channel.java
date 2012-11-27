/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;

import javax.media.*;
import javax.media.rtp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.cobri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.util.*;

import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.format.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.event.*;
import org.jitsi.service.neomedia.format.*;
import org.osgi.framework.*;

/**
 * Represents channel in the terms of Jitsi VideoBridge.
 *
 * @author Lyubomir Marinov
 */
public class Channel
{
    /**
     * The default number of seconds of inactivity after which <tt>Channel</tt>s
     * expire.
     */
    public static final int DEFAULT_EXPIRE = 60;

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
     * <tt>Channel</tt> listed in {@link #content} while this instance is listed
     * there as well).
     */
    private final String id;

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
    private long[] receiveSSRCs = CobriConferenceIQ.NO_SSRCS;

    /**
     * The type of RTP-level relay (in the terms specified by RFC 3550 "RTP: A
     * Transport Protocol for Real-Time Applications" in section 2.3 "Mixers and
     * Translators") used for this <tt>Channel</tt>.
     */
    private final RTPLevelRelayType rtpLevelRelayType;

    /**
     * THe <tt>MediaStream</tt> which this <tt>Channel</tt> adapts to the terms
     * of Jitsi VideoBridge and which adapts this <tt>Channel</tt> to the terms
     * of <tt>neomedia</tt>.
     */
    private final MediaStream stream;

    /**
     * The <tt>StreamConnector</tt> which is is to be the <tt>connector</tt> of
     * {@link #stream}.
     */
    private final StreamConnector streamConnector;

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

        streamConnector = createStreamConnector();

        stream = getMediaService().createMediaStream(mediaType);
        /*
         * Add the PropertyChangeListener to the MediaStream prior to performing
         * further initialization so that we do not miss changes to the values
         * of properties we may be interested in.
         */
        stream.addPropertyChangeListener(streamPropertyChangeListener);
        stream.setConnector(streamConnector);
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
                 * Allow the Jitsi VideoBridge server to send the audio levels
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

        stream.start();

        touch();
    }

    /**
     * Determines whether a specific <tt>DatagramPacket</tt> received on the
     * control <tt>DatagramSocket</tt> of this <tt>Channel</tt> is to be
     * accepted for further processing within Jitsi VideoBridge or is to be
     * rejected/dropped. When the first <tt>DatagramPacket</tt> arrives, makes
     * its source the only acceptable one for the control
     * <tt>DatagramSocket</tt> and uses it as the control target of
     * {@link #rtpConnector}.
     *
     * @param p the <tt>DatagramPacket</tt> received on the control
     * <tt>DatagramSocket</tt> of this <tt>Channel</tt>
     * @return <tt>true</tt> if the specified <tt>DatagramPacket</tt> is to be
     * accepted for further processing within Jitsi VideoBridge or
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
                         * VideoBridge server.
                         */
                        long ssrc = RTPTranslatorImpl.readInt(data, offset + 4);

                        removeReceiveSSRC(ssrc);
                    }
                }
            }
        }

        return accept;
    }

    /**
     * Determines whether a specific <tt>DatagramPacket</tt> received on the
     * data <tt>DatagramSocket</tt> of this <tt>Channel</tt> is to be accepted
     * for further processing within Jitsi VideoBridge or is to be
     * rejected/dropped. When the first <tt>DatagramPacket</tt> arrives, makes
     * its source the only acceptable one for the data <tt>DatagramSocket</tt>
     * and uses it as the data target of {@link #rtpConnector}.
     *
     * @param p the <tt>DatagramPacket</tt> received on the data
     * <tt>DatagramSocket</tt> of this <tt>Channel</tt>
     * @return <tt>true</tt> if the specified <tt>DatagramPacket</tt> is to be
     * accepted for further processing within Jitsi VideoBridge or
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
                int offset = p.getOffset();

                if (/* v */ ((data[offset] & 0xc0) >>> 6) == 2)
                {
                    /*
                     * The focus who has organized the telephony conference of
                     * this Channel receives the RTP streams of the participants
                     * on a single Channel. Consequently, it is unable to
                     * associate the participants with the SSRCs of the RTP
                     * streams that they send. The information will be provided
                     * to the focus by the Jitsi VideoBridge server.
                     */
                    long ssrc = RTPTranslatorImpl.readInt(data, offset + 8);

                    addReceiveSSRC(ssrc);

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
                            int pt = data[offset + 1] & 0x7f;
                            MediaFormat format
                                = payloadTypes.get(Byte.valueOf((byte) pt));

                            if ((format != null)
                                    && !format.equals(stream.getFormat()))
                            {
                                stream.setFormat(format);
                                stream.setDirection(MediaDirection.SENDRECV);
                            }
                        }
                    }
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
     */
    private synchronized void addReceiveSSRC(long receiveSSRC)
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
                return;
            }
        }

        // add
        long[] newReceiveSSRCs = new long[length + 2];

        System.arraycopy(receiveSSRCs, 0, newReceiveSSRCs, 0, length);
        newReceiveSSRCs[length] = receiveSSRC;
        newReceiveSSRCs[length + 1] = now;
        receiveSSRCs = newReceiveSSRCs;

        receiveSSRCsChanged();
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
        /*
         * Determine the local InetAddress the new StreamConnector is to attempt
         * to bind to.
         */
        ComponentImpl component
            = getContent().getConference().getVideoBridge().getComponent();
        BundleContext bundleContext = component.getBundleContext();
        NetworkAddressManagerService nams
            = ServiceUtils.getService(
                    bundleContext,
                    NetworkAddressManagerService.class);
        InetAddress bindAddr = null;

        if (nams != null)
        {
            String domain = component.getDomain();

            if ((domain != null) && (domain.length() != 0))
            {
                /*
                 * The domain reported by the Jabber component contains its
                 * dedicated subdomain and the goal here is to get the domain of
                 * the XMPP server.
                 */
                int subdomainEnd = domain.indexOf('.');

                if (subdomainEnd >= 0)
                    domain = domain.substring(subdomainEnd + 1);
                if (domain.length() != 0)
                {
                    try
                    {
                        bindAddr
                            = nams.getLocalHost(
                                    NetworkUtils.getInetAddress(domain));
                    }
                    catch (UnknownHostException uhe)
                    {
                        uhe.printStackTrace(System.err);
                    }
                }
            }
        }
        if (bindAddr == null)
            bindAddr = InetAddress.getLocalHost();

        StreamConnector streamConnector = new DefaultStreamConnector(bindAddr);

        /*
         * Try to follow the convention that the RTCP port is the next one after
         * the RTP port.
         */
        streamConnector.getDataSocket();
        streamConnector.getControlSocket();

        return streamConnector;
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>CobriConferenceIQ.Channel</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     *
     * @param iq the <tt>CobriConferenceIQ.Channel</tt> to set the values of the
     * properties of this instance on
     */
    public void describe(CobriConferenceIQ.Channel iq)
    {
        iq.setExpire(getExpire());
        iq.setHost(getHost());
        iq.setID(getID());
        iq.setRTCPPort(getRTCPPort());
        iq.setRTPPort(getRTPPort());
        iq.setSSRCs(getReceiveSSRCs());
    }

    /**
     * Expires this <tt>Channel</tt>.
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
                t.printStackTrace(System.err);
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }
            finally
            {
                try
                {
                    streamConnector.close();
                }
                catch (Throwable t)
                {
                    t.printStackTrace(System.err);
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

    public String getHost()
    {
        return
            streamConnector.getDataSocket().getLocalAddress().getHostAddress();
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
            return CobriConferenceIQ.NO_SSRCS;
        else
        {
            long[] receiveSSRCs = new long[length / 2];

            for (int src = 0, dst = 0; src < length; src += 2, dst++)
                receiveSSRCs[dst] = this.receiveSSRCs[src];
            return receiveSSRCs;
        }
    }

    public int getRTCPPort()
    {
        return streamConnector.getControlSocket().getLocalPort();
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

    public int getRTPPort()
    {
        return streamConnector.getDataSocket().getLocalPort();
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
                         * Whatever, the Jitsi VideoBridge is not interested in
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
     * Notifies this instance that the value of the <tt>receiveSSRCs</tt>
     * property/field of this instance has changed i.e. at least one RTP SSRC
     * has been added to or removed from the list of SSRCs received on this
     * <tt>Channel</tt>.
     */
    private void receiveSSRCsChanged()
    {
        /*
         * Notify the focus who has organized the telephony conference
         * associated with this instance in order to allow the focus to build
         * SSRC-CallPeer associates.
         */
        boolean interrupted = false;

        try
        {
            Content content = getContent();
            Conference conference = content.getConference();
            CobriConferenceIQ conferenceIQ = new CobriConferenceIQ();

            conference.describe(conferenceIQ);

            CobriConferenceIQ.Content contentIQ
                = conferenceIQ.getOrCreateContent(content.getName());
            CobriConferenceIQ.Channel channelIQ
                = new CobriConferenceIQ.Channel();

            describe(channelIQ);
            contentIQ.addChannel(channelIQ);

            conferenceIQ.setTo(conference.getFocus());
            conferenceIQ.setType(org.jivesoftware.smack.packet.IQ.Type.SET);

            conference.getVideoBridge().getComponent().send(conferenceIQ);
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
     */
    private synchronized void removeReceiveSSRC(long receiveSSRC)
    {
        final int length = receiveSSRCs.length;
        boolean removed = false;

        if (length == 2)
        {
            if (receiveSSRCs[0] == receiveSSRC)
            {
                receiveSSRCs = CobriConferenceIQ.NO_SSRCS;
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

        if (removed)
            receiveSSRCsChanged();
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
         * TODO The following is based on an internal property which has been
         * introduced specifically for Jitsi VideoBridge and should not be used
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
}
