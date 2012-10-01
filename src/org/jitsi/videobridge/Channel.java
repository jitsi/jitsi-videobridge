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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.util.*;

import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.format.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
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

        streamConnector = createStreamConnector();

        stream
            = getMediaService().createMediaStream(this.content.getMediaType());
        stream.setConnector(streamConnector);
        stream.setName(this.id);
        stream.setRTPTranslator(this.content.getRTPTranslator());
        stream.addPropertyChangeListener(streamPropertyChangeListener);
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
     * Gets the <tt>BundleContext</tt> associated with this <tt>Channel</tt>.
     * The method is a convenience which gets the <tt>BundleContext</tt>
     * associated with the XMPP component implementation in which the
     * <tt>VideoBridge</tt> associated with this instance is executing.
     *
     * @return the <tt>BundleContext</tt> associated with this <tt>Channel</tt>
     */
    private BundleContext getBundleContext()
    {
        return
            getContent().getConference().getVideoBridge().getComponent()
                    .getBundleContext();
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
        MediaService mediaService
            = ServiceUtils.getService(getBundleContext(), MediaService.class);

        /*
         * TODO For an unknown reason, ServiceUtils.getService fails to retrieve
         * the MediaService implementation. In the form of a temporary
         * workaround, get it through LibJitsi.
         */
        if (mediaService == null)
            mediaService = LibJitsi.getMediaService();

        return mediaService;
    }

    public int getRTCPPort()
    {
        return streamConnector.getControlSocket().getLocalPort();
    }

    public int getRTPPort()
    {
        return streamConnector.getDataSocket().getLocalPort();
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
                    = propertyName.substring(0, prefix.length());
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
