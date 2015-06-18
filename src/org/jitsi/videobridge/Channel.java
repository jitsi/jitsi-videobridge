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

import java.io.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.eventadmin.*;
import org.osgi.framework.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

/**
 * Represents channel in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Pawel Domas
 */
public abstract class Channel
    extends PropertyChangeNotifier
{
    /**
     * The default number of seconds of inactivity after which <tt>Channel</tt>s
     * expire.
     */
    public static final int DEFAULT_EXPIRE = 60;

    /**
     * The name of the <tt>Channel</tt> property which indicates whether the
     * conference focus is the initiator/offerer (as opposed to the
     * responder/answerer) of the media negotiation associated with the
     * <tt>Channel</tt>.
     */
    public static final String INITIATOR_PROPERTY = "initiator";

    /**
     * The <tt>Logger</tt> used by the <tt>Channel</tt> class and its instances
     * to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Channel.class);

    /**
     * The ID of the channel-bundle that this <tt>Channel</tt> is part of, or
     * <tt>null</tt> if it is not part of a channel-bundle.
     */
    private final String channelBundleId;

    /**
     * The name of the <tt>Channel</tt> property <tt>endpoint</tt> which
     * points to the <tt>Endpoint</tt> of the conference participant associated
     * with this <tt>Channel</tt>..
     */
    public static final String ENDPOINT_PROPERTY_NAME = ".endpoint";

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
     * The <tt>StreamConnector</tt> currently used by this <tt>Channel</tt>.
     */
    private StreamConnector streamConnector;

    /**
     * The <tt>TransportManager</tt> that represents the Jingle transport of
     * this <tt>Channel</tt>.
     */
    private TransportManager transportManager;

    /**
     * Transport packet extension namespace used by {@link #transportManager}.
     * Indicates whether ICE or RAW transport is used by this channel.
     */
    private final String transportNamespace;

    /**
     * The <tt>Object</tt> which synchronizes the access to
     * {@link #transportManager}.
     */
    private final Object transportManagerSyncRoot = new Object();

    /**
     * Initializes a new <tt>Channel</tt> instance which is to have a specific
     * ID. The initialization is to be considered requested by a specific
     * <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id unique string identifier of this instance
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>AudioChannel</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     * @param transportNamespace the namespace of transport used by this
     * channel. Can be either {@link IceUdpTransportPacketExtension#NAMESPACE}
     * or {@link RawUdpTransportPacketExtension#NAMESPACE}.
     * @param initiator the value to use for the initiator field, or
     * <tt>null</tt> to use the default value.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public Channel(Content content, String id, String channelBundleId,
                   String transportNamespace, Boolean initiator)
        throws Exception
    {
        if (content == null)
            throw new NullPointerException("content");
        if (StringUtils.isNullOrEmpty(id))
            throw new NullPointerException("id");

        this.id = id;
        this.content = content;
        this.channelBundleId = channelBundleId;
        if (initiator != null)
            this.initiator = initiator;

        // Get default transport namespace
        if (StringUtils.isNullOrEmpty(transportNamespace))
        {
            transportNamespace
                = getContent().getConference()
                    .getVideobridge().getDefaultTransportManager();
        }

        this.transportNamespace = transportNamespace;

        touch();
    }

    /**
     * Called when <tt>Channel</tt> is being expired. Derived class should close
     * any open streams.
     */
    protected abstract void closeStream()
        throws IOException;

    /**
     * Initializes the pair of <tt>DatagramSocket</tt>s for RTP and RTCP
     * traffic.
     *
     * @return a new <tt>StreamConnector</tt> instance which represents the pair
     * of <tt>DatagramSocket</tt>s for RTP and RTCP traffic
     * <tt>rtpConnector</tt> is to use
     */
    protected StreamConnector createStreamConnector()
    {
        TransportManager transportManager = getTransportManager();
        return transportManager != null
            ? transportManager.getStreamConnector(this)
            : null;
    }

    /**
     * Initializes a <tt>MediaStreamTarget</tt> instance which identifies the
     * remote addresses to transmit RTP and RTCP to and from.
     *
     * @return a <tt>MediaStreamTarget</tt> instance which identifies the
     * remote addresses to transmit RTP and RTCP to and from
     */
    protected MediaStreamTarget createStreamTarget()
    {
        TransportManager transportManager = getTransportManager();
        return transportManager != null
            ? transportManager.getStreamTarget(this)
            : null;
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
    protected TransportManager createTransportManager(String xmlNamespace)
        throws IOException
    {
        if (IceUdpTransportPacketExtension.NAMESPACE.equals(xmlNamespace))
        {
            Content content = getContent();

            return
                new IceUdpTransportManager(
                        content.getConference(),
                        isInitiator(),
                        2 /* numComponents */,
                        content.getName());
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
    public void describe(ColibriConferenceIQ.ChannelCommon iq)
    {
        Endpoint endpoint = getEndpoint();

        if (endpoint != null)
            iq.setEndpoint(endpoint.getID());

        iq.setID(id);
        iq.setExpire(getExpire());
        iq.setInitiator(isInitiator());

        // If a channel is part of a bundle, its transport will be described
        // in the channel-bundle itself
        if (channelBundleId != null)
        {
            iq.setChannelBundleId(channelBundleId);
        }
        else
        {
            describeTransportManager(iq);
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
    private void describeTransportManager(ColibriConferenceIQ.ChannelCommon iq)
    {
        TransportManager  transportManager = getTransportManager();

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
        Conference conference = content.getConference();

        EventAdmin eventAdmin
                = conference.getVideobridge().getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.sendEvent(EventFactory.channelExpired(this));
        try
        {
            content.expireChannel(this);
        }
        finally
        {
            // stream
            try
            {
                closeStream();
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

            // transportManager
            try
            {
                synchronized (transportManagerSyncRoot)
                {
                    if (transportManager != null)
                        transportManager.close(this);
                }
            }
            catch (Throwable t)
            {
                logger.warn(
                        "Failed to close the TransportManager/transportManager"
                            + " of channel " + getID() + " of content "
                            + content.getName() + " of conference "
                            + conference.getID() + "!",
                        t);
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }

            // endpoint
            try
            {
                // Remove this Channel from the Endpoint. Accomplished by
                // pretending that the Endpoint associated with this Channel has
                // changed to null.
                onEndpointChanged(getEndpoint(), null);
            }
            catch (Throwable t)
            {
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }

            Videobridge videobridge = conference.getVideobridge();

            if (logger.isInfoEnabled())
            {

                logger.info(
                        "Expired channel " + getID() + " of content "
                            + content.getName() + " of conference "
                            + conference.getID() + ". "
                            + videobridge.getConferenceCountString());
            }
        }
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
     * Gets the <tt>Content</tt> which has initialized this <tt>Channel</tt>.
     *
     * @return the <tt>Content</tt> which has initialized this <tt>Content</tt>
     */
    public final Content getContent()
    {
        return content;
    }

    /**
     * Child classes should implement this method and return
     * <tt>DtlsControl</tt> instance if they are willing to use DTLS transport.
     * Otherwise <tt>null</tt> should be returned.
     *
     * @return <tt>DtlsControl</tt> if this instance supports DTLS transport or
     *         <tt>null</tt> otherwise.
     */
    protected DtlsControl getDtlsControl()
    {
        TransportManager transportManager = getTransportManager();
        return transportManager != null
            ? transportManager.getDtlsControl(this)
            : null;
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
     * Gets the <tt>StreamConnector</tt> currently used by this instance.
     * @return the <tt>StreamConnector</tt> currently used by this instance.
     */
    StreamConnector getStreamConnector()
    {
        if (streamConnector == null)
        {
            streamConnector = createStreamConnector();
        }
        return streamConnector;
    }

    /**
     * Gets the <tt>TransportManager</tt> for this <tt>Channel</tt>.
     * @return the <tt>TransportManager</tt> for this <tt>Channel</tt>.
     */
    public TransportManager getTransportManager()
    {
        return transportManager;
    }

    /**
     * Initializes this channel. Creates transport manager for
     * {@link #transportNamespace} or obtains instance from {@link Conference}
     * if "bundle" is being used.
     * @throws IOException in case of transport manager initialization error
     */
    public void initialize()
            throws IOException
    {
        synchronized (transportManagerSyncRoot)
        {
            // If this channel is not part of a channel-bundle, it creates
            // its own TransportManager
            if (channelBundleId == null)
            {
                transportManager
                    = createTransportManager(transportNamespace);
            }
            // Otherwise, it uses a TransportManager specific to the
            // channel-bundle, which is maintained by the Conference object.
            else
            {
                transportManager
                    = getContent().getConference()
                        .getTransportManager(channelBundleId, true);

            }

            if (transportManager == null)
                throw new IOException("Failed to get transport manager.");

            transportManager.addChannel(this);
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
     * TODO: update this javadoc
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
    protected abstract void maybeStartStream() throws IOException;

    /**
     * Called when new <tt>Endpoint</tt> is being set on this <tt>Channel</tt>.
     *
     * @param oldValue old <tt>Endpoint</tt>, can be <tt>null</tt>.
     * @param newValue new <tt>Endpoint</tt>, can be <tt>null</tt>.
     */
    protected void onEndpointChanged(Endpoint oldValue, Endpoint newValue)
    {
        firePropertyChange(ENDPOINT_PROPERTY_NAME, oldValue, newValue);
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
                this.endpoint = newValue;

                onEndpointChanged(oldValue, newValue);
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
            /* TODO Handle the change of initiator? Or remove the functionality
               on channel-level?

            DtlsControl dtlsControl = getDtlsControl();

            if(dtlsControl != null)
            {
                dtlsControl.setSetup(
                    isInitiator()
                        ? DtlsControl.Setup.PASSIVE
                        : DtlsControl.Setup.ACTIVE);
            }
            */

            firePropertyChange(INITIATOR_PROPERTY, oldValue, newValue);
        }
    }

    /**
     * Sets a specific <tt>IceUdpTransportPacketExtension</tt> on this
     * <tt>Channel</tt>.
     *
     * @param transport the <tt>IceUdpTransportPacketExtension</tt> to be set on
     * this <tt>Channel</tt>
     */
    public void setTransport(IceUdpTransportPacketExtension transport)
    {
        if (transport != null)
        {
            TransportManager transportManager = getTransportManager();
            if (transportManager != null)
                transportManager.startConnectivityEstablishment(transport);
            else
                logger.warn("Failed to start connectivity establishment: "
                            + "transport manager is null.");
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

    /**
     * Notifies this <tt>Channel</tt> that its <tt>TransportManager</tt> has
     * been closed.
     */
    void transportClosed()
    {
        expire();
    }

    /**
     * Notifies this <tt>Channel</tt> that its <tt>TransportManager</tt> has
     * established connectivity.
     */
    void transportConnected()
    {
        logger.info("Transport connected for channel " + getID()
                            + " of content " + getContent().getName()
                            + " of conference "
                            + getContent().getConference().getID());
        try
        {
            maybeStartStream();
        }
        catch (IOException ioe)
        {
            logger.warn("Failed to start stream for channel: " + getID()
                                + ": " + ioe);
        }
    }

    /**
     * Returns the ID of channel-bundle of this <tt>Channel</tt>, or
     * <tt>null</tt> if the <tt>Channel</tt> is not part of a channel-bundle.
     * @return  the ID of channel-bundle of this <tt>Channel</tt>, or
     * <tt>null</tt> if the <tt>Channel</tt> is not part of a channel-bundle.
     */
    public String getChannelBundleId()
    {
        return channelBundleId;
    }
}
