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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import net.java.sip.communicator.util.*;
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
            {
                pe = new IceUdpTransportPacketExtension();
            }
            else
            {
                pe = null;
            }

            iq.setTransport(pe);
        }
        if (pe != null)
        {
            describe(pe);
        }
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
     * NOTE(brian): we only support rtcpmux as of now, so no need for this one anymore
     */
    @Deprecated
    public void describe(ColibriConferenceIQ.ChannelCommon iq)
    {
        IceUdpTransportPacketExtension pe = iq.getTransport();
        String namespace = getXmlNamespace();

        if ((pe == null) || !namespace.equals(pe.getNamespace()))
        {
            if (IceUdpTransportPacketExtension.NAMESPACE.equals(namespace))
            {
                pe = new IceUdpTransportPacketExtension();
            }
            else
            {
                pe = null;
            }

            iq.setTransport(pe);
        }
        if (pe != null)
        {
            describe(pe);
        }
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
     * Checks whether this transport manager has established connectivity.
     *
     * @return <tt>true</tt> iff this transport manager has established
     * connectivity.
     */
    public abstract boolean isConnected();
}
