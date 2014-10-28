/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.rtcp.*;
import org.jitsi.videobridge.sim.*;
import org.json.simple.*;

/**
 * Implements an <tt>RtpChannel</tt> with <tt>MediaType.VIDEO</tt>.
 *
 * @author Lyubomir Marinov
 */
public class VideoChannel
    extends RtpChannel
{
    /**
     * The <tt>Logger</tt> used by the <tt>VideoChannel</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(VideoChannel.class);

    /**
     * The property which controls whether Jitsi Videobridge will perform
     * replacement of the timestamps in the abs-send-time RTP header extension.
     */
    private static final String DISABLE_ABS_SEND_TIME_PNAME =
            "org.jitsi.videobridge.DISABLE_ABS_SEND_TIME";

    /**
     * The length in milliseconds of the interval for which the average incoming
     * bitrate for this video channel will be computed and made available
     * through {@link #getIncomingBitrate}.
     */
    private static final int INCOMING_BITRATE_INTERVAL_MS = 5000;

    /**
     * The <tt>SimulcastManager</tt> of this video <tt>Channel</tt>.
     */
    private final SimulcastManager simulcastManager;

    /**
     * The maximum number of video RTP stream to be sent from Jitsi Videobridge
     * to the endpoint associated with this video <tt>Channel</tt>.
     */
    private Integer lastN;

    /**
     * The <tt>Endpoint</tt>s in the multipoint conference in which this
     * <tt>Channel</tt> is participating ordered by
     * {@link #conferenceSpeechActivity} and used by this <tt>Channel</tt> for
     * the support of {@link #lastN}.
     */
    private List<WeakReference<Endpoint>> lastNEndpoints;

    /**
     * The <tt>VideoChannelLastNAdaptor</tt> which will be controlling the
     * value of <tt>lastN</tt> for this <tt>VideoChannel</tt>, if the adaptive
     * lastN feature is enabled.
     */
    private VideoChannelLastNAdaptor lastNAdaptor;

    /**
     * The instance which will be computing the incoming bitrate for this
     * <tt>VideoChannel</tt>.
     */
    private RateStatistics incomingBitrate
            = new RateStatistics(INCOMING_BITRATE_INTERVAL_MS, 8000F);

    /**
     * Whether or not to use adaptive lastN.
     */
    private boolean adaptiveLastN = false;

    /**
     * The <tt>Object</tt> which synchronizes the access to
     * {@link #lastNEndpoints} and {@link #lastN}.
     */
    private final ReadWriteLock lastNSyncRoot = new ReentrantReadWriteLock();

    /**
     * Initializes a new <tt>VideoChannel</tt> instance which is to have a
     * specific ID. The initialization is to be considered requested by a
     * specific <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>VideoChannel</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     * @throws Exception if an error occurs while initializing the new instance
     */
    public VideoChannel(Content content, String id, String channelBundleId)
        throws Exception
    {
        super(content, id, channelBundleId);
        this.simulcastManager = new SimulcastManager(this);
    }

    /**
     * Initializes a new <tt>VideoChannel</tt> instance which is to have a
     * specific ID. The initialization is to be considered requested by a
     * specific <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public VideoChannel(Content content, String id)
            throws Exception
    {
        this(content, id, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;

        super.describe(iq);

        iq.setLastN(lastN);
    }

    /**
     * Gets the maximum number of video RTP streams to be sent from Jitsi
     * Videobridge to the endpoint associated with this video <tt>Channel</tt>.
     *
     * @return the maximum number of video RTP streams to be sent from Jitsi
     * Videobridge to the endpoint associated with this video <tt>Channel</tt>.
     * If no value or <tt>null</tt> has been explicitly set or this is not a
     * video <tt>Channel</tt>, returns <tt>-1</tt>.
     */
    int getLastN()
    {
        Integer lastNInteger = this.lastN;

        return (lastNInteger == null) ? -1 : lastNInteger.intValue();
    }

    /**
     * Gets the <tt>SimulcastManager</tt> of this <tt>VideoChannel</tt>.
     * @return the simulcast manager of this <tt>VideoChannel</tt>.
     */
    public SimulcastManager getSimulcastManager()
    {
        return this.simulcastManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInLastN(Channel channel)
    {
        int lastN = getLastN();

        if (lastN < 0)
            return true;

        Endpoint channelEndpoint = channel.getEndpoint();

        if (channelEndpoint == null)
            return true;

        ConferenceSpeechActivity conferenceSpeechActivity
            = this.conferenceSpeechActivity;

        if (conferenceSpeechActivity == null)
            return true;
        if (lastN == 0)
            return false;

        /*
         * We do not hold any lock on lastNSyncRoot here because it should be
         * alright for multiple threads to check whether lastNEndpoints is null
         * and invoke the method to populate it because (1) the method to
         * populate lastNEndpoints will acquire the necessary locks to ensure
         * preserving the correctness of the state of this instance under the
         * conditions of concurrent access and (2) we do not want to hold a
         * write lock on lastNSyncRoot while invoking the method to populate
         * lastNEndpoints because the latter might fire an event.
         */
        if (lastNEndpoints == null)
        {
            /*
             * Pretend that the ordered list of Endpoints maintained by
             * conferenceSpeechActivity has changed in order to populate
             * lastNEndpoints.
             */
            speechActivityEndpointsChanged(null);
        }

        Lock readLock = lastNSyncRoot.readLock();
        boolean inLastN = false;

        readLock.lock();
        try
        {
            if (lastNEndpoints != null)
            {
                Endpoint thisEndpoint = getEndpoint();
                int n = 0;

                for (WeakReference<Endpoint> wr : lastNEndpoints)
                {
                    Endpoint e = wr.get();

                    if (e != null)
                    {
                        if (e.equals(thisEndpoint))
                        {
                            continue;
                        }
                        else if (e.equals(channelEndpoint))
                        {
                            inLastN = true;
                            break;
                        }
                    }

                    ++n;
                    if (n >= lastN)
                        break;
                }
            }
        }
        finally
        {
            readLock.unlock();
        }
        return inLastN;
    }

    /**
     * Notifies this instance that the list of <tt>Endpoint</tt>s defined by
     * {@link #lastN} has changed.
     *
     * @param endpointsEnteringLastN the <tt>Endpoint</tt>s which are entering
     * the list of <tt>Endpoint</tt>s defined by <tt>lastN</tt>
     */
    private void lastNEndpointsChanged(List<Endpoint> endpointsEnteringLastN)
    {
        int lastN = getLastN();

        if (lastN < 0)
            return;

        Endpoint endpoint = getEndpoint();

        if (endpoint == null)
            return;

        // Represent the list of Endpoints defined by lastN in JSON format.
        Lock readLock = lastNSyncRoot.readLock();
        StringBuilder lastNEndpointsStr = new StringBuilder();
        /*
         * We want endpointsEnteringLastN to always to reported. Consequently,
         * we will pretend that all lastNEndpoints are entering if no explicit
         * endpointsEnteringLastN is specified.
         */
        List<Endpoint> effectiveEndpointsEnteringLastN = endpointsEnteringLastN;

        if (effectiveEndpointsEnteringLastN == null)
            effectiveEndpointsEnteringLastN = new ArrayList<Endpoint>(lastN);

        readLock.lock();
        try
        {
            if ((lastNEndpoints != null) && !lastNEndpoints.isEmpty())
            {
                int n = 0;

                for (WeakReference<Endpoint> wr : lastNEndpoints)
                {
                    if (n >= lastN)
                        break;
                    Endpoint e = wr.get();

                    if (e != null)
                    {
                        if (e.equals(endpoint))
                        {
                            continue;
                        }
                        else
                        {
                            if (lastNEndpointsStr.length() != 0)
                                lastNEndpointsStr.append(',');
                            lastNEndpointsStr.append('"');
                            lastNEndpointsStr.append(
                                    JSONValue.escape(e.getID()));
                            lastNEndpointsStr.append('"');

                            if (effectiveEndpointsEnteringLastN
                                    != endpointsEnteringLastN)
                            {
                                effectiveEndpointsEnteringLastN.add(e);
                            }
                        }
                    }

                    ++n;
                }
            }
        }
        finally
        {
            readLock.unlock();
        }

        // colibriClass
        StringBuilder msg
            = new StringBuilder(
                    "{\"colibriClass\":\"LastNEndpointsChangeEvent\"");

        // lastNEndpoints
        msg.append(",\"lastNEndpoints\":[");
        msg.append(lastNEndpointsStr);
        msg.append(']');

        // endpointsEnteringLastN
        /*
         * We want endpointsEnteringLastN to always to reported. Consequently,
         * we will pretend that all lastNEndpoints are entering if no explicit
         * endpointsEnteringLastN is specified.
         */
        endpointsEnteringLastN = effectiveEndpointsEnteringLastN;
        if ((endpointsEnteringLastN != null)
                && !endpointsEnteringLastN.isEmpty())
        {
            StringBuilder endpointsEnteringLastNStr = new StringBuilder();

            for (Endpoint e : endpointsEnteringLastN)
            {
                if (endpointsEnteringLastNStr.length() != 0)
                    endpointsEnteringLastNStr.append(',');
                endpointsEnteringLastNStr.append('"');
                endpointsEnteringLastNStr.append(
                        JSONValue.escape(e.getID()));
                endpointsEnteringLastNStr.append('"');
            }
            if (endpointsEnteringLastNStr.length() != 0)
            {
                msg.append(",\"endpointsEnteringLastN\":[");
                msg.append(endpointsEnteringLastNStr);
                msg.append(']');
            }
        }

        msg.append('}');
        try
        {
            endpoint.sendMessageOnDataChannel(msg.toString());
        }
        catch (IOException e)
        {
            logger.error("Failed to send message on data channel.", e);
        }
    }

    /**
     * Gets the index of a specific <tt>Endpoint</tt> in a specific list of
     * <tt>lastN</tt> <tt>Endpoint</tt>s.
     *
     * @param endpoints the list of <tt>Endpoint</tt>s into which to look for
     * <tt>endpoint</tt>
     * @param lastN the number of <tt>Endpoint</tt>s in <tt>endpoint</tt>s to
     * look through
     * @param endpoint the <tt>Endpoint</tt> to find within <tt>lastN</tt>
     * elements of <tt>endpoints</tt>
     * @return the <tt>lastN</tt> index of <tt>endpoint</tt> in
     * <tt>endpoints</tt> or <tt>-1</tt> if <tt>endpoint</tt> is not within the
     * <tt>lastN</tt> elements of <tt>endpoints</tt>
     */
    private int lastNIndexOf(
            List<Endpoint> endpoints,
            int lastN,
            Endpoint endpoint)
    {
        Endpoint thisEndpoint = getEndpoint();
        int n = 0;

        for (Endpoint e : endpoints)
        {
            if (n >= lastN)
                break;

            if (e.equals(thisEndpoint))
                continue;
            else if (e.equals(endpoint))
                return n;

            ++n;
        }
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean rtpTranslatorWillWrite(
            boolean data,
            byte[] buffer, int offset, int length,
            Channel source)
    {
        boolean accept = true;

        if (data && (source != null))
        {
            accept = isInLastN(source);

            if (accept && source instanceof VideoChannel)
            {
                VideoChannel videoChannel = (VideoChannel) source;
                accept = simulcastManager.accept(
                        buffer, offset, length, videoChannel);
            }
        }

        return accept;
    }

    /**
     * {@inheritDoc}
     *
     * Fires initial events over the WebRTC data channel of this
     * <tt>VideoChannel</tt> such as the list of last-n <tt>Endpoint</tt>s whose
     * video is sent/RTP translated by this <tt>RtpChannel</tt> to its
     * <tt>Endpoint</tt>.
     */
    @Override
    void sctpConnectionReady(Endpoint endpoint)
    {
        super.sctpConnectionReady(endpoint);

        if (endpoint.equals(getEndpoint()))
        {
            lastNEndpointsChanged(null);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastN(Integer lastN)
    {
        if (this.lastN == lastN)
            return;

        // If the old value was null, even though we may detect endpoints
        // "entering" lastN, they are already being received and so
        // no keyframes are necessary.
        boolean askForKeyframes = this.lastN == null;

        Lock writeLock = lastNSyncRoot.writeLock();
        List<Endpoint> endpointsEnteringLastN = new LinkedList <Endpoint>();

        writeLock.lock();
        try
        {
            if (this.lastN != null && this.lastN >= 0)
            {
                if (lastN > this.lastN)
                {
                    Endpoint thisEndpoint = getEndpoint();
                    int n = 0;
                    for (WeakReference<Endpoint> wr : lastNEndpoints)
                    {
                        if (n >= lastN)
                            break;

                        Endpoint endpoint = wr.get();
                        if (endpoint != null && endpoint.equals(thisEndpoint))
                        {
                            continue;
                        }

                        ++n;
                        if (n > this.lastN && endpoint != null)
                            endpointsEnteringLastN.add(endpoint);
                    }
                }
            }

            this.lastN = lastN;
        }
        finally
        {
            writeLock.unlock();
        }

        lastNEndpointsChanged(endpointsEnteringLastN);

        if (askForKeyframes)
        {
            getContent().askForKeyframes(
                    new HashSet<Endpoint>(endpointsEnteringLastN));
        }

        touch(); // It seems this Channel is still active.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    List<Endpoint> speechActivityEndpointsChanged(List<Endpoint> endpoints)
    {
        Lock writeLock = lastNSyncRoot.writeLock();
        List<Endpoint> endpointsEnteringLastN = null;
        boolean lastNEndpointsChanged = false;

        writeLock.lock();
        try
        {
            // Determine which Endpoints are entering the list of lastN.
            int lastN = getLastN();

            if (endpoints == null)
            {
                endpoints = conferenceSpeechActivity.getEndpoints();
            }
            if (lastN >= 0)
            {
                Endpoint thisEndpoint = getEndpoint();

                // At most the first lastN are entering the list of lastN.
                endpointsEnteringLastN = new ArrayList<Endpoint>(lastN);
                for (Endpoint e : endpoints)
                {
                    if (endpointsEnteringLastN.size() >= lastN)
                        break;
                    if (!e.equals(thisEndpoint))
                    {
                        endpointsEnteringLastN.add(e);
                    }
                }

                if (lastNEndpoints != null && !lastNEndpoints.isEmpty())
                {
                    /*
                     * Some of these first lastN are already in the list of
                     * lastN.
                     */
                    int n = 0;

                    for (WeakReference<Endpoint> wr : lastNEndpoints)
                    {
                        if (n >= lastN)
                            break;

                        Endpoint e = wr.get();

                        if (e != null)
                        {
                            if (e.equals(thisEndpoint))
                            {
                                continue;
                            }
                            else
                            {
                                endpointsEnteringLastN.remove(e);
                                if (lastNIndexOf(endpoints, lastN, e) < 0)
                                    lastNEndpointsChanged = true;
                            }
                        }

                        ++n;
                    }
                }
            }

            // Remember the Endpoints for the purposes of lastN.
            lastNEndpoints
                = new ArrayList<WeakReference<Endpoint>>(endpoints.size());
            for (Endpoint endpoint : endpoints)
                lastNEndpoints.add(new WeakReference<Endpoint>(endpoint));
        }
        finally
        {
            writeLock.unlock();
        }

        if (endpointsEnteringLastN != null
                && !endpointsEnteringLastN.isEmpty())
        {
            lastNEndpointsChanged = true;
        }

        // Notify about changes in the list of lastN.
        if (lastNEndpointsChanged)
            lastNEndpointsChanged(endpointsEnteringLastN);

        // Request keyframes from the Endpoints entering the list of lastN.
        return endpointsEnteringLastN;
    }

    /**
     * Notifies this <tt>VideoChannel</tt> that an RTCP REMB packet with a
     * bitrate value of <tt>remb</tt> bits per second was received.
     *
     * @param remb the bitrate of the received REMB packet in bits per second.
     */
    public void receivedREMB(long remb)
    {
        if (adaptiveLastN)
        {
            VideoChannelLastNAdaptor lastNAdaptor = getLastNAdaptor();
            if (lastNAdaptor != null)
                lastNAdaptor.receivedREMB(remb);
        }
    }

    /**
     * Returns the <tt>VideoChannelLastNAdaptor</tt> for this
     * <tt>VideoChannel</tt>, creating it if necessary.
     * @return the <tt>VideoChannelLastNAdaptor</tt> for this
     * <tt>VideoChannel</tt>, creating it if necessary.
     */
    private VideoChannelLastNAdaptor getLastNAdaptor()
    {
        if (lastNAdaptor == null)
        {
            lastNAdaptor = new VideoChannelLastNAdaptor(this);
        }
        return lastNAdaptor;
    }

    /**
     * {@inheritDoc}
     *
     * Enables the the abs-send-time extension after the stream has been
     * started.
     */
    @Override
    protected void maybeStartStream()
        throws IOException
    {
        super.maybeStartStream();

        MediaStream stream = getStream();
        if (stream != null)
        {
            ConfigurationService cfg
                    = ServiceUtils.getService(
                    getBundleContext(),
                    ConfigurationService.class);

            boolean disableAbsSendTime
                = cfg != null
                    && cfg.getBoolean (DISABLE_ABS_SEND_TIME_PNAME, false);

            if (!disableAbsSendTime)
            {
                // TODO: remove hard-coded value
                stream.setAbsSendTimeExtensionID(3);
            }
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p)
    {
        boolean accept = super.acceptDataInputStreamDatagramPacket(p);

        if (accept)
        {
            // TODO: find a way to do this only in case it is actually needed
            // (currently this means when there is another channel in the
            // same content, with adaptive-last-n turned on), in order to not
            // waste resources.
            incomingBitrate.update(p.getLength(), System.currentTimeMillis());

            // With native simulcast we don't have a notification when a stream
            // has started/stopped. The simulcast manager implements a timeout
            // for the high quality stream and it needs to be notified when
            // the channel has accepted a datagram packet for the timeout to
            // function correctly.
            simulcastManager.acceptedDataInputStreamDatagramPacket(p);
        }

        return accept;
    }

    /**
     * Returns the current incoming bitrate in bits per second for this
     * <tt>VideoChannel</tt> (computed as the average bitrate over the last
     * {@link #INCOMING_BITRATE_INTERVAL_MS} milliseconds).
     * @return the current incoming bitrate for this <tt>VideoChannel</tt>.
     */
    long getIncomingBitrate()
    {
        return incomingBitrate.getRate(System.currentTimeMillis());
    }

    /**
     * Returns the list of endpoints for the purposes of lastN.
     * @return the list of endpoints for the purposes of lastN.
     */
    List<WeakReference<Endpoint>> getLastNEndpoints()
    {
        List<WeakReference<Endpoint>> endpoints
                = new LinkedList<WeakReference<Endpoint>>();

        Lock readLock = lastNSyncRoot.readLock();

        readLock.lock();
        try
        {
            if (lastNEndpoints != null)
                endpoints.addAll(lastNEndpoints);
        }
        finally
        {
            readLock.unlock();
        }

        return endpoints;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAdaptiveLastN(boolean adaptiveLastN)
    {
        this.adaptiveLastN = adaptiveLastN;

        if (adaptiveLastN)
        {
            // Ensure that we are using BasicBridgeRTCPTerminationStrategy,
            // which is currently needed to notify us of incoming REMBs
            getContent().setRTCPTerminationStrategyFQN(
                    BasicBridgeRTCPTerminationStrategy.class.getCanonicalName());
        }
    }
}
