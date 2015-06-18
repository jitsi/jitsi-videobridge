/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.beans.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * Represents the state of a Jingle transport.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public abstract class TransportManager
{
    /**
     * The ID to be assigned to the next transport candidate.
     */
    private static long nextCandidateID = 1;

    /**
     * The <tt>Logger</tt> used by the <tt>TransportManager</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(TransportManager.class);

    /**
     * The <tt>PropertyChangeListener</tt> which listens to changes in the
     * values of the properties of <tt>Channel</tt>-s in {@link #channels}.
     * Facilitates extenders.
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
     * The <tt>Channel</tt> which has initialized this instance
     */
    private final List<Channel> channels = new LinkedList<Channel>();

    /**
     * Initializes a new <tt>TransportManager</tt> instance.
     */
    protected TransportManager()
    {
    }

    /**
     * Adds a <tt>Channel</tt> to the list of channels maintained by this
     * <tt>TransportManager</tt>.
     * @param channel the <tt>Channel</tt> to add.
     * @return <tt>true</tt> if the <tt>Channel</tt> was added, <tt>false</tt>
     * if the channel was already in the list.
     */
    public boolean addChannel(Channel channel)
    {
        synchronized (channels)
        {
            if (!channels.contains(channel))
            {
                channels.add(channel);
                channel.addPropertyChangeListener(
                        channelPropertyChangeListener);
                return true;
            }
        }
        return false;
    }

    /**
     * Notifies this <tt>TransportManager</tt> that the value of a property of a
     * <tt>Channel</tt> from {@link #channels} has changed from a specific old
     * value to a specific new value.
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
        synchronized (channels)
        {
            for (Channel channel : channels)
                close(channel);
        }
    }

    /**
     * Removes a <tt>Channel</tt> from the list of channels maintained by this
     * <tt>TransportManager</tt>.
     * @param channel the <tt>Channel</tt> to remove.
     * @return <tt>true</tt> if the <tt>Channel</tt> was removed, <tt>false</tt>
     * if the channel was not in the list.
     */
    public boolean close(Channel channel)
    {
        if (channel == null)
            return false;

        channel.removePropertyChangeListener(channelPropertyChangeListener);

        synchronized (channels)
        {
            boolean removed = channels.remove(channel);

            // Remove transport manager from parent conference and it's
            // corresponding channel bundle ID description.
            if (removed && channels.isEmpty())
            {
                channel.getContent().getConference().closeTransportManager(
                        this);
            }
            return removed;
        }
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.ChannelBundle</tt>
     * to the values of the respective properties of this instance. Thus, the
     * specified <tt>iq</tt> may be thought of as containing a description of
     * this instance.
     *
     * @param iq the <tt>ColibriConferenceIQ.Channel</tt> on which to set the
     * values of the properties of this instance
     */
    public void describe(ColibriConferenceIQ.ChannelBundle iq)
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
     * <tt>ColibriConferenceIQ.Channel</tt>
     * to the values of the respective properties of this instance. Thus, the
     * specified <tt>iq</tt> may be thought of as containing a description of
     * this instance.
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
     * <tt>IceUdpTransportPacketExtension</tt>
     * to the values of the respective properties of this instance. Thus, the
     * specified <tt>pe</tt> may be thought of as a description of this
     * instance.
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
     * Returns the list of <tt>Channel</tt>s maintained by this
     * <tt>TransportManager</tt>.
     * @return the list of <tt>Channel</tt>s maintained by this
     * <tt>TransportManager</tt>.
     */
    protected List<Channel> getChannels()
    {
        synchronized (channels)
        {
            return channels;
        }
    }

    /**
     * Returns the <tt>DtlsControl</tt> allocated by this instance for use by a
     * specific <tt>Channel</tt>.
     * @param channel the <tt>Channel</tt> for which to return the
     * <tt>DtlsControl</tt>.
     * @return the <tt>DtlsControl</tt> allocated by this instance for use by a
     * specific <tt>Channel</tt>.
     */
    public abstract DtlsControl getDtlsControl(Channel channel);

    /**
     * Gets the <tt>StreamConnector</tt> which represents the datagram sockets
     * allocated by this instance for the purposes of a specific
     * <tt>Channel</tt>.
     *
     * @param channel the <tt>Channel</tt> for which to return the
     * <tt>StreamConnector</tt>.
     * @return the <tt>StreamConnector</tt> which represents the datagram
     * sockets allocated by this instance for the purposes of a specific
     * <tt>Channel</tt>.
     */
    public abstract StreamConnector getStreamConnector(Channel channel);

    /**
     * Gets the <tt>MediaStreamTarget</tt> which represents the remote addresses
     * to transmit RTP and RTCP to and from. A non-<tt>null</tt> remote address
     * will disable latching on the associated component.
     *
     * @return the <tt>MediaStreamTarget</tt> which represents the remote
     * addresses to transmit RTP and RTCP to and from
     */
    public abstract MediaStreamTarget getStreamTarget(Channel channel);

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
     */
    public abstract void startConnectivityEstablishment(
            IceUdpTransportPacketExtension transport);

    /**
     * Notifies this <tt>TransportManager</tt> that the configured RTP Payload
     * Type numbers for one of its <tt>RtpChannel</tt>s have been updated.
     *
     * @param channel
     */
    public void payloadTypesChanged(RtpChannel channel)
    {
        checkPayloadTypes(channel);
    }

    /**
     * Logs a warning if the addition of <tt>channel</tt> to <tt>channels</tt>
     * will result in the same Payload Type number being received by more than
     * one channel (which is bound to cause issues and probably indicates a
     * problem on the signalling side).
     *
     * @param channel the <tt>Channel</tt> being added.
     */
    private void checkPayloadTypes(RtpChannel channel)
    {
        synchronized (channels)
        {
            for (Channel c : channels)
            {
                if ( !(c instanceof RtpChannel) || c == channel)
                    continue;

                // We only have a couple of PTs for each channel and this does
                // not executes often.
                for (int pt1 : ((RtpChannel) c).getReceivePTs())
                    for (int pt2 : channel.getReceivePTs())
                    {
                        if (pt1 == pt2)
                            logger.warn("The same PT (" + pt1 + ") used by two "
                                            + "channels in the same bundle.");
                    }
            }
        }
    }

}

