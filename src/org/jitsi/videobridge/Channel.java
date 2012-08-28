/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.net.*;
import java.util.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.format.*;
import javax.media.protocol.*;
import javax.media.rtp.*;

import net.java.sip.communicator.impl.neomedia.*;
import net.java.sip.communicator.impl.neomedia.format.*;
import net.java.sip.communicator.impl.neomedia.transform.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.neomedia.*;
import net.java.sip.communicator.service.neomedia.format.*;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.util.*;

import org.ice4j.socket.*;
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

    private final RTPTransformUDPConnector rtpConnector;

    /**
     * The <tt>SessionAddress</tt> which is or is to be the target of
     * {@link #rtpConnector}. When <tt>DatagramPacket</tt>s are received through
     * the <tt>DatagramSocket</tt>s of this <tt>Channel</tt>, their first RTP
     * and RTCP sources will determine, respectively, the RTP and RTCP targets.
     */
    private final SessionAddress rtpConnectorTarget = new SessionAddress();

    /**
     * The <tt>SendStream</tt> created by this <tt>Channel</tt> in order to
     * enable the {@link RTPTranslator} implementation to send media.
     */
    private SendStream sendStream;

    /**
     * The <tt>SrtpControl</tt> which deals with the media security of this
     * <tt>Channel</tt>.
     */
    private final SrtpControl srtpControl;

    private final StreamRTPManager streamRTPManager;

    public Channel(Content content, String id)
        throws Exception
    {
        if (content == null)
            throw new NullPointerException("content");
        if (id == null)
            throw new NullPointerException("id");

        this.content = content;
        this.id = id;

        streamRTPManager
            = new StreamRTPManager(this.content.getRTPTranslator());

        rtpConnector
            = new RTPTransformUDPConnector(createStreamConnector())
            {
                @Override
                protected TransformUDPInputStream createControlInputStream()
                    throws IOException
                {
                    TransformUDPInputStream controlInputStream
                        = super.createControlInputStream();

                    /*
                     * When the first DatagramPacket arrives on the control
                     * DatagramSocket, make its source the only acceptable one
                     * and use it as the control target of rtpConnector.
                     */
                    controlInputStream.addDatagramPacketFilter(
                            new DatagramPacketFilter()
                            {
                                public boolean accept(DatagramPacket p)
                                {
                                    return
                                        acceptControlInputStreamDatagramPacket(
                                                p);
                                }
                            });
                    return controlInputStream;
                }

                @Override
                protected TransformUDPInputStream createDataInputStream()
                    throws IOException
                {
                    TransformUDPInputStream dataInputStream
                        = super.createDataInputStream();

                    /*
                     * When the first DatagramPacket arrives on the data
                     * DatagramSocket, make its source the only acceptable one
                     * and use it as the data target of rtpConnector.
                     */
                    dataInputStream.addDatagramPacketFilter(
                            new DatagramPacketFilter()
                            {
                                public boolean accept(DatagramPacket p)
                                {
                                    return
                                        acceptDataInputStreamDatagramPacket(p);
                                }
                            });
                    return dataInputStream;
                }
            };

        // ZRTP
        BundleContext bundleContext
            = this.content.getConference().getVideoBridge().getComponent()
                    .getBundleContext();
        SrtpControl srtpControl = null;

        if (bundleContext != null)
        {
            MediaService mediaService
                = ServiceUtils.getService(bundleContext, MediaService.class);

            if (mediaService != null)
            {
                srtpControl = mediaService.createZrtpControl();
                srtpControl.setConnector(rtpConnector);
            }
        }
        this.srtpControl = srtpControl;
        rtpConnector.setEngine(createTransformEngineChain());

        streamRTPManager.initialize(rtpConnector);

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
        InetAddress controlAddress = rtpConnectorTarget.getControlAddress();
        boolean accept;

        if (controlAddress == null)
        {
            rtpConnectorTarget.setControlHostAddress(p.getAddress());
            rtpConnectorTarget.setControlPort(p.getPort());

            if (rtpConnectorTarget.getDataAddress() != null)
            {
                try
                {
                    getRTPConnector().addTarget(rtpConnectorTarget);
                }
                catch (IOException ioe)
                {
                    ioe.printStackTrace(System.err);
                }
            }

            accept = true;
        }
        else
        {
            accept
                = controlAddress.equals(p.getAddress())
                    && (rtpConnectorTarget.getControlPort() == p.getPort());
        }

        // Note that this Channel is still active.
        if (accept)
            touch();

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
        InetAddress dataAddress = rtpConnectorTarget.getDataAddress();
        boolean accept;

        if (dataAddress == null)
        {
            rtpConnectorTarget.setDataHostAddress(p.getAddress());
            rtpConnectorTarget.setDataPort(p.getPort());

            try
            {
                getRTPConnector().addTarget(rtpConnectorTarget);
            }
            catch (IOException ioe)
            {
                ioe.printStackTrace(System.err);
            }

            accept = true;
        }
        else
        {
            accept
                = dataAddress.equals(p.getAddress())
                    && (rtpConnectorTarget.getDataPort() == p.getPort());
        }

        // Note that this Channel is still active.
        if (accept)
            touch();

        return accept;
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
     * Initializes a new <tt>TransformEngineChain</tt> instance which is to be
     * used as the <tt>TransformEngine</tt> of {@link #rtpConnector}.
     *
     * @return a new <tt>TransformEngineChain</tt> instance which is to be used
     * as the <tt>TransformEngine</tt> of <tt>rtpConnector</tt>
     */
    private TransformEngineChain createTransformEngineChain()
    {
        SrtpControl srtpControl = getSrtpControl();
        List<TransformEngine> transformEngines
            = new LinkedList<TransformEngine>();

        if (srtpControl != null)
            transformEngines.add(srtpControl.getTransformEngine());

        return
            new TransformEngineChain(
                    transformEngines.toArray(
                            new TransformEngine[transformEngines.size()]));
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
            SrtpControl srtpControl = getSrtpControl();

            if (srtpControl != null)
                srtpControl.cleanup();

            getRTPConnector().removeTargets();

            try
            {
                getStreamRTPManager().dispose();
            }
            catch (Throwable t)
            {
                t.printStackTrace(System.err);
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
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
            getRTPConnector().getConnector().getDataSocket().getLocalAddress()
                    .getHostAddress();
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

    public int getRTCPPort()
    {
        return
            getRTPConnector().getConnector().getControlSocket().getLocalPort();
    }

    public AbstractRTPConnector getRTPConnector()
    {
        return rtpConnector;
    }

    public int getRTPPort()
    {
        return getRTPConnector().getConnector().getDataSocket().getLocalPort();
    }

    /**
     * Gets the <tt>SrtpControl</tt> which deals with the media security of this
     * <tt>Channel</tt>.
     *
     * @return the <tt>SrtpControl</tt> which deals with the media security of
     * this <tt>Channel</tt>
     */
    public SrtpControl getSrtpControl()
    {
        return srtpControl;
    }

    public StreamRTPManager getStreamRTPManager()
    {
        return streamRTPManager;
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
         * StreamRTPManager about them.
         */
        StreamRTPManager streamRTPManager = getStreamRTPManager();
        List<Format> formats = new ArrayList<Format>();

        if ((payloadTypes != null) && (payloadTypes.size() > 0))
        {
            BundleContext bundleContext
                = getContent().getConference().getVideoBridge().getComponent()
                        .getBundleContext();

            if (bundleContext != null)
            {
                MediaService mediaService
                    = ServiceUtils.getService(
                            bundleContext,
                            MediaService.class);

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
                            @SuppressWarnings("unchecked")
                            Format format
                                = ((MediaFormatImpl<? extends Format>)
                                        mediaFormat)
                                    .getFormat();

                            streamRTPManager.addFormat(
                                    format,
                                    payloadType.getID());
                            formats.add(format);
                        }
                    }
                }
            }
        }

        /*
         * As noted elsewhere, a SendStream must be created to allow the
         * RTPTranslator implementation to send media.
         */
        if (sendStream == null)
        {
            PushBufferDataSource dataSource = getContent().getDataSource();
            PushBufferStream[] streams = dataSource.getStreams();
            int streamCount = streams.length;

            for (int streamIndex = 0; streamIndex < streamCount; streamIndex++)
            {
                PushBufferStream stream = streams[streamIndex];

                /*
                 * If Formats have been set on this Channel, use one of them for
                 * the SendStream. It does not really matter because the
                 * DataSource will not provide any media.
                 */
                if (formats.size() > 0)
                {
                    FormatControl formatControl
                        = (FormatControl)
                            stream.getControl(FormatControl.class.getName());

                    if (formatControl != null)
                        formatControl.setFormat(formats.get(0));
                }

                /*
                 * If the DataSource turns out to have a Format which is not
                 * registered with the StreamRTPManager, register the Format in
                 * question with a made-up payload type (number). It imposes a
                 * slight risk but it should not happen anyway if Formats have
                 * actually been set on this Channel.
                 */
                Format format = stream.getFormat();

                if ((format != null) && !formats.contains(format))
                {
                    streamRTPManager.addFormat(
                            format,
                            (format instanceof AudioFormat) ? 0 : 99);
                }

                try
                {
                    sendStream
                        = streamRTPManager.createSendStream(
                                dataSource,
                                streamIndex);
                }
                catch (IOException ioe)
                {
                    ioe.printStackTrace(System.err);
                }
                catch (UnsupportedFormatException ufe)
                {
                    ufe.printStackTrace(System.err);
                }

                if (sendStream != null)
                    break;
            }
        }

        touch(); // It seems this Channel is still active.
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
