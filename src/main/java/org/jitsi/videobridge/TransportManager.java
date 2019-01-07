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
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import net.java.sip.communicator.util.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.nlj.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;

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
     * The default value of the minimum port to use for dynamic allocation.
     */
    public static final int DEFAULT_MIN_PORT = 10001;

    /**
     * The default value of the maximum port to use for dynamic allocation.
     */
    public static final int DEFAULT_MAX_PORT = 20000;

    /**
     * The {@link PortTracker} instance used by jitsi-videobridge to manage
     * dynamic port allocation.
     */
    public static final PortTracker portTracker
        = new PortTracker(DEFAULT_MIN_PORT, DEFAULT_MAX_PORT);

    /**
     * The <tt>Channel</tt> which has initialized this instance
     */
//    private List<Channel> _channels = Collections.emptyList();

    /**
     * The {@code Object} which synchronizes the access to {@link #_channels}.
     */
    private final Object _channelsSyncRoot = new Object();

    /**
     * Initializes a new <tt>TransportManager</tt> instance.
     */
    protected TransportManager()
    {
    }

    /**
     * Releases the resources acquired by this <tt>TransportManager</tt> and
     * prepares it for garbage collection.
     */
    public void close()
    {
//        for (Channel channel : getChannels())
//            close(channel);
    }

    /**
     * Removes a <tt>Channel</tt> from the list of channels maintained by this
     * <tt>TransportManager</tt>.
     *
     * @param channel the <tt>Channel</tt> to remove.
     * @return <tt>true</tt> if the <tt>Channel</tt> was removed, <tt>false</tt>
     * if the channel was not in the list.
     */
//    public boolean close(Channel channel)
//    {
//        if (channel == null)
//            return false;
//
//        channel.removePropertyChangeListener(channelPropertyChangeListener);
//
//        synchronized (_channelsSyncRoot)
//        {
//            List<Channel> newChannels = new LinkedList<>(_channels);
//            boolean removed = newChannels.remove(channel);
//
//            if (removed)
//            {
//                _channels = newChannels;
//
//                // Remove transport manager from parent conference and its
//                // corresponding channel bundle ID description.
//                if (getChannels().isEmpty())
//                {
//                    channel.getContent().getConference().closeTransportManager(
//                            this);
//                }
//            }
//            return removed;
//        }
//    }

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
     *
     * @return the list of <tt>Channel</tt>s maintained by this
     * <tt>TransportManager</tt>.
     */
//    protected List<Channel> getChannels()
//    {
//        return _channels;
//    }

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

//    /**
//     * Notifies this <tt>TransportManager</tt> that the configured RTP Payload
//     * Type numbers for one of its <tt>RtpChannel</tt>s have been updated.
//     *
//     * @param channel
//     */
//    public void payloadTypesChanged(RtpChannel channel)
//    {
//        checkPayloadTypes(channel);
//    }

    //TODO(brian): maybe we want to keep this logic (and reimplement it)?
//    /**
//     * Logs a warning if the addition of <tt>channel</tt> to <tt>_channels</tt>
//     * will result in the same Payload Type number being received by more than
//     * one channel (which is bound to cause issues and probably indicates a
//     * problem on the signalling side).
//     *
//     * @param channel the <tt>Channel</tt> being added.
//     */
//    private void checkPayloadTypes(RtpChannel channel)
//    {
//        for (Channel c : getChannels())
//        {
//            if (!(c instanceof RtpChannel) || c == channel)
//                continue;
//
//            // We only have a couple of PTs for each channel and this does not
//            // execute often.
//            for (int pt1 : ((RtpChannel) c).getReceivePTs())
//                for (int pt2 : channel.getReceivePTs())
//                {
//                    if (pt1 == pt2)
//                        logger.warn(
//                                "The same PT (" + pt1 + ") used by two "
//                                    + "channels in the same bundle.");
//                }
//        }
//    }

    /**
     * Checks whether this transport manager has established connectivity.
     *
     * @return <tt>true</tt> iff this transport manager has established
     * connectivity.
     */
    public abstract boolean isConnected();

    public void onTransportConnected(Runnable handler) {
        //TODO: revisit whether this method makes sense long term and, if so, clean it up when we clean up the
        // transport manager object hierarchy/which types will stick around/etc.
        // no op by default
    }

    /**
     * @return the {@link TransportCCEngine} instance, if any, associated with
     * this transport channel.
     */
    public TransportCCEngine getTransportCCEngine()
    {
        return null;
    }
}
