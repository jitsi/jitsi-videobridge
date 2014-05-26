/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.beans.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.service.neomedia.*;

/**
 * Represents the state of a Jingle transport as used by {@link Channel}.
 *
 * @author Lyubomir Marinov
 */
public abstract class TransportManager
{
    /**
     * The ID to be assigned to the next transport candidate.
     */
    private static long nextCandidateID = 1;

    /**
     * The <tt>Channel</tt> which has initialized this instance
     */
    private final Channel channel;

    /**
     * The <tt>PropertyChangeListener</tt> which listens to changes in the
     * values of the properties of {@link #channel}. Facilitates extenders.
     */
    private final PropertyChangeListener channelPropertyChangeListener
        = new PropertyChangeListener()
                {
                    @Override
                    public void propertyChange(PropertyChangeEvent ev)
                    {
                        channelPropertyChange(ev);
                    }
                };
    /**
     * Initializes a new <tt>TransportManager</tt> instance.
     *
     * @param channel the <tt>Channel</tt> which is initializing the new
     * instance
     */
    protected TransportManager(Channel channel)
    {
        this.channel = channel;

        if (this.channel != null)
        {
            this.channel.addPropertyChangeListener(
                    channelPropertyChangeListener);
        }
    }

    /**
     * Notifies this <tt>TransportManager</tt> that the value of a property of
     * {@link #channel} has changed from a specific old value to a specific new
     * value.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the name of the
     * property which had its value changed and the old and new values of that
     * property
     */
    protected void channelPropertyChange(PropertyChangeEvent ev)
    {
    }

    /**
     * Releases the resources acquired by this <tt>TransportManager</tt> and
     * prepares it for garbage collection.
     */
    public void close()
    {
        Channel channel = getChannel();

        if (channel != null)
            channel.removePropertyChangeListener(channelPropertyChangeListener);
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.Channel</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as containing a description of this instance.
     *
     * @param iq the <tt>ColibriConferenceIQ.Channel</tt> on which to set the
     * values of the properties of this instance
     */
    public void describe(ColibriConferenceIQ.ChannelCommon iq)
    {
        IceUdpTransportPacketExtension pe = iq.getTransport();
        String namespace = getXmlNamespace();

        if ((pe == null) || !namespace.equals(pe.getNamespace()))
        {
            if (IceUdpTransportPacketExtension.NAMESPACE.equals(namespace))
                pe = new IceUdpTransportPacketExtension();
            else if (RawUdpTransportPacketExtension.NAMESPACE.equals(namespace))
                pe = new RawUdpTransportPacketExtension();
            else
                pe = null;

            iq.setTransport(pe);
        }
        if (pe != null)
            describe(pe);
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>IceUdpTransportPacketExtension</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>pe</tt> may be
     * thought of as a description of this instance.
     *
     * @param pe the <tt>IceUdpTransportPacketExtension</tt> on which to set the
     * values of the properties of this instance
     */
    protected abstract void describe(IceUdpTransportPacketExtension pe);

    /**
     * Generates a new ID to be used for a transport candidate.
     *
     * @return a new ID to be used fro a transport candidate
     */
    protected String generateCandidateID()
    {
        long candidateID;

        synchronized (TransportManager.class)
        {
            candidateID = nextCandidateID++;
        }
        return Long.toHexString(candidateID);
    }

    /**
     * Gets the <tt>Channel</tt> which has initialized this instance.
     *
     * @return the <tt>Channel</tt> which has initialized this instance
     */
    public final Channel getChannel()
    {
        return channel;
    }

    /**
     * Gets the <tt>StreamConnector</tt> which represents the datagram sockets
     * allocated by this instance for the purposes of RTP and RTCP transmission.
     *
     * @return the <tt>StreamConnector</tt> which represents the datagram
     * sockets allocated by this instance for the purposes of RTP and RTCP
     * transmission
     */
    public abstract StreamConnector getStreamConnector();

    /**
     * Gets the <tt>MediaStreamTarget</tt> which represents the remote addresses
     * to transmit RTP and RTCP to and from. A non-<tt>null</tt> remote address
     * will disable latching on the associated component.
     *
     * @return the <tt>MediaStreamTarget</tt> which represents the remote
     * addresses to transmit RTP and RTCP to and from
     */
    public abstract MediaStreamTarget getStreamTarget();

    /**
     * Gets the XML namespace of the Jingle transport implemented by this
     * <tt>TransportManager</tt>.
     *
     * @return the XML namespace of the Jingle transport implemented by this
     * <tt>TransportManager</tt>
     */
    public abstract String getXmlNamespace();

    /**
     * Starts establishing connectivity between the local endpoint represented
     * by this instance and the remote endpoint represented by a specific
     * <tt>IceUdpTransportPacketExtension</tt>. The method should return
     * quickly. If the connectivity establishment requires a lengthy amount of
     * time to complete, the method should start executing it asynchronously.
     * 
     * @param transport an <tt>IceUdpTransportPacketExtension</tt> which
     * specifies connectivity-related information about the remote endpoint
     * @return <tt>true</tt> if asynchronous connectivity establishment has been
     * started and the results of it are to be awaited by invoking
     * {@link #wrapupConnectivityEstablishment()}; otherwise, <tt>false</tt>.
     * <tt>TransportManager</tt>s which do not perform/require connectivity
     * establishment should return <tt>false</tt>.
     */
    public abstract boolean startConnectivityEstablishment(
            IceUdpTransportPacketExtension transport);

    /**
     * Awaits for any ongoing connectivity establishment started by
     * {@link #startConnectivityEstablishment(IceUdpTransportPacketExtension)}
     * to complete.
     *
     * @throws OperationFailedException if the connectivity establishment has
     * failed
     */
    public abstract void wrapupConnectivityEstablishment()
        throws OperationFailedException;
}
