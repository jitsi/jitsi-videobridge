/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.osgi.framework.*;

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
        = ExecutorUtils.newCachedThreadPool(true, "Channel");

    /**
     * The name of the <tt>Channel</tt> property which indicates whether the
     * conference focus is the initiator/offerer (as opposed to the
     * responder/answerer) of the media negotiation associated with the
     * <tt>Channel</tt>.
     */
    public static final String INITIATOR_PROPERTY = "initiator";

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
     * @throws Exception if an error occurs while initializing the new instance
     */
    public Channel(Content content)
        throws Exception
    {
        if (content == null)
            throw new NullPointerException("content");
        this.content = content;

        touch();
    }

    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level
     */
    protected static void logd(String s)
    {
        logger.debug(s);
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
    protected StreamConnector createStreamConnector()
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
    protected MediaStreamTarget createStreamTarget()
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
    public void describe(ColibriConferenceIQ.ChannelCommon iq)
    {
        Endpoint endpoint = getEndpoint();

        if (endpoint != null)
            iq.setEndpoint(endpoint.getID());

        iq.setExpire(getExpire());
        iq.setInitiator(isInitiator());

        describeTransportManager(iq);
        describeSrtpControl(iq);
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.ChannelCommon</tt> to the values of the
     * respective properties of {@link #transportManager}.
     *
     * @param iq the <tt>ColibriConferenceIQ.ChannelCommon</tt> on which to set
     *           the values of the properties of <tt>transportManager</tt>
     */
    private void describeSrtpControl(ColibriConferenceIQ.ChannelCommon iq)
    {
        DtlsControl dtlsControl = getDtlsControl();

        if (dtlsControl != null)
        {
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
    private void describeTransportManager(ColibriConferenceIQ.ChannelCommon iq)
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
                    wrapupConnectivityEstablishmentCommand = null;
                    if (transportManager != null)
                        transportManager.close();
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
                Endpoint endpoint = getEndpoint();
                // Handle new null Endpoint == remove from Endpoint
                onEndpointChanged(endpoint, null);
            }
            catch (Throwable t)
            {
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

    /**
     * Called when <tt>Channel</tt> is being expired. Derived class should close
     * any open streams.
     */
    protected abstract void closeStream()
        throws IOException;

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
     * Child classes should implement this method and return
     * <tt>DtlsControl</tt> instance if they are willing to use DTLS transport.
     * Otherwise <tt>null</tt> should be returned.
     *
     * @return <tt>DtlsControl</tt> if this instance supports DTLS transport or
     *         <tt>null</tt> otherwise.
     */
    protected abstract DtlsControl getDtlsControl();

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
    public abstract String getID();

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
     * Gets the <tt>TransportManager</tt> which represents the Jingle transport
     * of this <tt>Channel</tt>. If the <tt>TransportManager</tt> has not been
     * created yet, it is created.
     *
     * @return the <tt>TransportManager</tt> which represents the Jingle
     * transport of this <tt>Channel</tt>
     */
    protected TransportManager getTransportManager()
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
    protected abstract void maybeStartStream() throws IOException;

    /**
     * Called when new <tt>Endpoint</tt> is being set on this <tt>Channel</tt>.
     *
     * @param oldValue old <tt>Endpoint</tt>, can be <tt>null</tt>.
     * @param newValue new <tt>Endpoint</tt>, can be <tt>null</tt>.
     */
    protected abstract void onEndpointChanged(Endpoint oldValue,
                                              Endpoint newValue);

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
            DtlsControl dtlsControl = getDtlsControl();

            if(dtlsControl != null)
            {
                dtlsControl.setSetup(
                    isInitiator()
                        ? DtlsControl.Setup.PASSIVE
                        : DtlsControl.Setup.ACTIVE);
            }

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
        throws IOException
    {
        if (transport != null)
        {
            setTransportManager(transport.getNamespace());

            // DTLS-SRTP
            DtlsControl dtlsControl = getDtlsControl();

            if (dtlsControl != null)
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
