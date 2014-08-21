/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.lang.ref.*;
import java.util.*;

import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.rtcp.*;
import org.osgi.framework.*;

/**
 * Represents a content in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class Content
    implements RTPTranslator.WriteFilter
{
    /**
     * The <tt>Logger</tt> used by the <tt>Content</tt> class and its instances
     * to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Content.class);

    /**
     * The <tt>Channel</tt>s of this <tt>Content</tt> mapped by their IDs.
     */
    private final Map<String,Channel> channels = new HashMap<String,Channel>();

    /**
     * The <tt>Conference</tt> which has initialized this <tt>Content</tt>.
     */
    private final Conference conference;

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Content</tt>.
     */
    private boolean expired = false;

    /**
     * The local synchronization source identifier (SSRC) associated with this
     * <tt>Content</tt>, which is to to be pre-announced by the
     * <tt>Channel</tt>s of this <tt>Content</tt>.
     *
     * Currently, the value is taken into account in the case of RTP translation.
     */
    private long initialLocalSSRC = -1;

    /**
     * The time in milliseconds of the last activity related to this
     * <tt>Content</tt>. In the time interval between the last activity and now,
     * this <tt>Content</tt> is considered inactive.
     */
    private long lastActivityTime;

    /**
     * The <tt>MediaType</tt> of this <tt>Content</tt>. The implementation
     * detects the <tt>MediaType</tt> by looking at the {@link #name} of this
     * instance.
     */
    private final MediaType mediaType;

    /**
     * The <tt>MediaDevice</tt> which mixes the media received by those of
     * {@link #channels} which use a mixer as their RTP-level relay.
     */
    private MediaDevice mixer;

    /**
     * The name of this <tt>Content</tt>.
     */
    private final String name;

    /**
     * The <tt>Recorder</tt> instance used to record video.
     */
    private Recorder recorder = null;

    /**
     * Whether media recording is currently enabled for this <tt>Content</tt>.
     */
    private boolean recording = false;

    /**
     * Path to the directory into which files relating to media recording for
     * this <tt>Content</tt> will be stored.
     */
    private String recordingPath = null;

    private RTCPFeedbackMessageSender rtcpFeedbackMessageSender;

    /**
     * The <tt>Object</tt> which synchronizes the access to the RTP-level relays
     * (i.e. {@link #mixer} and {@link #rtpTranslator}) provided by this
     * <tt>Content</tt>.
     */
    private final Object rtpLevelRelaySyncRoot = new Object();

    /**
     * The <tt>RTPTranslator</tt> which forwards the RTP and RTCP traffic
     * between those {@link #channels} which use a translator as their RTP-level
     * relay.
     */
    private RTPTranslator rtpTranslator;

    /**
     * Initializes a new <tt>Content</tt> instance which is to be a part of a
     * specific <tt>Conference</tt> and which is to have a specific name.
     *
     * @param conference the <tt>Conference</tt> which is initializing the new
     * instance
     * @param name the name of the new instance
     */
    public Content(Conference conference, String name)
    {
        if (conference == null)
            throw new NullPointerException("conference");
        if (name == null)
            throw new NullPointerException("name");

        this.conference = conference;
        this.name = name;

        mediaType = MediaType.parseString(this.name);

        touch();
    }

    @Override
    public boolean accept(
            MediaStream source,
            byte[] buffer, int offset, int length,
            MediaStream destination,
            boolean data)
    {
        boolean accept = true;

        if (destination != null)
        {
            RtpChannel dst = RtpChannel.getChannel(destination);

            if (dst != null)
            {
                RtpChannel src
                    = (source == null) ? null : RtpChannel.getChannel(source);

                accept
                    = dst.rtpTranslatorWillWrite(
                            data,
                            buffer, offset, length,
                            src);
            }
        }
        return accept;
    }

    void askForKeyframes(Set<Endpoint> endpoints)
    {
        for (Endpoint endpoint : endpoints)
        {
            for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
                channel.askForKeyframes();
        }
    }

    /**
     * Initializes a new <tt>RtpChannel</tt> instance and adds it to the list of
     * <tt>RtpChannel</tt>s of this <tt>Content</tt>. The new
     * <tt>RtpChannel</tt> instance has an ID which is unique within the list of
     * <tt>RtpChannel</tt>s of this <tt>Content</tt>.
     *
     * @param channelBundleId the ID of the channel-bundle that the created
     * <tt>RtpChannel</tt> is to be a part of (or <tt>null</tt> if it is not to
     * be a part of a channel-bundle).
     * @return the created <tt>RtpChannel</tt> instance.
     * @throws Exception
     */
    public RtpChannel createRtpChannel(String channelBundleId)
        throws Exception
    {
        RtpChannel channel = null;

        do
        {
            String id = generateChannelID();

            synchronized (channels)
            {
                if (!channels.containsKey(id))
                {
                    switch (getMediaType())
                    {
                    case AUDIO:
                        channel = new AudioChannel(this, id, channelBundleId);
                        break;
                    case DATA:
                        /*
                         * MediaType.DATA signals an SctpConnection, not an
                         * RtpChannel.
                         */
                        throw new IllegalStateException("mediaType");
                    case VIDEO:
                        channel = new VideoChannel(this, id, channelBundleId);
                        break;
                    default:
                        channel = new RtpChannel(this, id, channelBundleId);
                        break;
                    }
                    channels.put(id, channel);
                }
            }
        }
        while (channel == null);

        if (logger.isInfoEnabled())
        {
            /*
             * The method Videobridge.getChannelCount() should better be
             * executed outside synchronized blocks in order to reduce the risks
             * of causing deadlocks.
             */
            Conference conference = getConference();
            Videobridge videobridge = conference.getVideobridge();

            logger.info(
                    "Created channel " + channel.getID() + " of content "
                        + getName() + " of conference " + conference.getID()
                        + ". The total number of conferences is now "
                        + videobridge.getConferenceCount() + ", channels "
                        + videobridge.getChannelCount() + ".");
        }

        return channel;
    }

    /**
     * Creates new <tt>SctpConnection</tt> with given <tt>Endpoint</tt> on given
     * <tt>sctpPort</tt>.
     *
     * @param endpoint the <tt>Endpoint</tt> of <tt>SctpConnection</tt>
     * @param sctpPort remote SCTP port that will be used by new
     * <tt>SctpConnection</tt>.
     * @param channelBundleId the ID of the channel-bundle that the created
     * <tt>SctpConnection</tt> is to be a part of (or <tt>null</tt> if it is not
     * to be a part of a channel-bundle).
     * @return new <tt>SctpConnection</tt> with given <tt>Endpoint</tt>
     * @throws Exception if an error occurs while initializing the new instance
     * @throws IllegalArgumentException if <tt>SctpConnection</tt> exists
     * already for given <tt>Endpoint</tt>.
     */
    public SctpConnection createSctpConnection(Endpoint endpoint,
                                               int sctpPort,
                                               String channelBundleId)
        throws Exception
    {
        SctpConnection sctpConnection;

        synchronized (channels)
        {
            String id = generateChannelID();

            sctpConnection
                = new SctpConnection(
                        id, this, endpoint, sctpPort, channelBundleId);

            channels.put(sctpConnection.getID(), sctpConnection);
        }

        return sctpConnection;
    }

    /**
     * Expires this <tt>Content</tt> and its associated <tt>Channel</tt>s.
     * Releases the resources acquired by this instance throughout its life time
     * and prepares it to be garbage collected.
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

        setRecording(false, null);
        Conference conference = getConference();

        try
        {
            conference.expireContent(this);
        }
        finally
        {
            // Expire the Channels of this Content.
            for (Channel channel : getChannels())
            {
                try
                {
                    channel.expire();
                }
                catch (Throwable t)
                {
                    logger.warn(
                            "Failed to expire channel " + channel.getID()
                                + " of content " + getName() + " of conference "
                                + conference.getID() + "!",
                            t);
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                }
            }

            synchronized (rtpLevelRelaySyncRoot)
            {
                if (rtpTranslator != null)
                    rtpTranslator.dispose();
                rtcpFeedbackMessageSender = null;
            }

            if (logger.isInfoEnabled())
            {
                Videobridge videobridge = conference.getVideobridge();

                logger.info(
                        "Expired content " + getName() + " of conference "
                            + conference.getID()
                            + ". The total number of conferences is now "
                            + videobridge.getConferenceCount() + ", channels "
                            + videobridge.getChannelCount() + ".");
            }
        }
    }

    /**
     * Expires a specific <tt>Channel</tt> of this <tt>Content</tt> (i.e. if the
     * specified <tt>channel</tt> is not in the list of <tt>Channel</tt>s of
     * this <tt>Content</tt>, does nothing).
     *
     * @param channel the <tt>Channel</tt> to be expired by this
     * <tt>Content</tt>
     */
    public void expireChannel(Channel channel)
    {
        String id = channel.getID();
        boolean expireChannel;

        synchronized (channels)
        {
            if (channel.equals(channels.get(id)))
            {
                channels.remove(id);
                expireChannel = true;
            }
            else
                expireChannel = false;
        }
        if (expireChannel)
            channel.expire();
    }

    /**
     * If media recording is started, finds all SSRCs received on all channels,
     * and sets their endpoints to the <tt>Recorder</tt>'s <tt>Synchronizer</tt>
     * instance.
     */
    void feedKnownSsrcsToSynchronizer()
    {
        Recorder recorder;
        if (isRecording() && (recorder = getRecorder()) != null)
        {
            Synchronizer synchronizer = recorder.getSynchronizer();
            for (Channel channel : getChannels())
            {
                if (!(channel instanceof RtpChannel))
                    continue;
                Endpoint endpoint = channel.getEndpoint();
                if(endpoint == null)
                    continue;

                for(int s : ((RtpChannel) channel).getReceiveSSRCs())
                {
                    long ssrc = s & 0xffffffffl;
                    synchronizer.setEndpoint(ssrc, endpoint.getID());
                }
            }
        }
    }

    /**
     * XXX REMOVE
     * Returns a <tt>Channel</tt> of this <tt>Content</tt>, which has
     * <tt>ssrc</tt> in its list of received SSRCs, or <tt>null</tt> in case no
     * such <tt>Channel</tt> exists.
     * @param ssrc the ssrc to search for.
     * @return a <tt>Channel</tt> of this <tt>Content</tt>, which has
     * <tt>ssrc</tt> in its list of received SSRCs, or <tt>null</tt> in case no
     * such <tt>Channel</tt> exists.
     */
    Channel findChannel(long ssrc)
    {
        for (Channel channel : getChannels())
        {
            if (channel instanceof RtpChannel)
            {
                RtpChannel rtpChannel = (RtpChannel) channel;
                for (int channelSsrc : rtpChannel.getReceiveSSRCs())
                {
                    if (ssrc == (0xffffffffL & channelSsrc))
                        return channel;
                }
            }
        }

        return null;
    }

    /**
     * Returns a <tt>Channel</tt> of this <tt>Content</tt>, which has
     * <tt>receiveSSRC</tt> in its list of received SSRCs, or <tt>null</tt> in
     * case no such <tt>Channel</tt> exists.
     *
     * @param receiveSSRC the SSRC to search for.
     * @return a <tt>Channel</tt> of this <tt>Content</tt>, which has
     * <tt>receiveSSRC</tt> in its list of received SSRCs, or <tt>null</tt> in
     * case no such <tt>Channel</tt> exists.
     */
    Channel findChannelByReceiveSSRC(long receiveSSRC)
    {
        for (Channel channel : getChannels())
        {
            //FIXME: fix instanceof
            if(!(channel instanceof RtpChannel))
                continue;

            RtpChannel rtpChannel = (RtpChannel) channel;

            for (int channelReceiveSSRC : rtpChannel.getReceiveSSRCs())
            {
                if (receiveSSRC == (0xFFFFFFFFL & channelReceiveSSRC))
                    return channel;
            }
        }
        return null;
    }

    /**
     * Generates a new <tt>Channel</tt> ID which is not guaranteed to be unique.
     *
     * @return a new <tt>Channel</tt> ID which is not guaranteed to be unique
     */
    private String generateChannelID()
    {
        return
            Long.toHexString(
                    System.currentTimeMillis() + Videobridge.RANDOM.nextLong());
    }

    /**
     * Gets the <tt>BundleContext</tt> associated with this <tt>Content</tt>.
     * The method is a convenience which gets the <tt>BundleContext</tt>
     * associated with the XMPP component implementation in which the
     * <tt>Videobridge</tt> associated with this instance is executing.
     *
     * @return the <tt>BundleContext</tt> associated with this <tt>Content</tt>
     */
    public BundleContext getBundleContext()
    {
        return getConference().getBundleContext();
    }

    /**
     * Returns a <tt>Channel</tt> from the list of <tt>Channel</tt>s of this
     * <tt>Content</tt> which has a specific ID.
     *
     * @param id the ID of the <tt>Channel</tt> to be returned
     * @return a <tt>Channel</tt> from the list of <tt>Channel</tt>s of this
     * <tt>Content</tt> which has the specified <tt>id</tt> if such a
     * <tt>Channel</tt> exists; otherwise, <tt>null</tt>
     */
    public Channel getChannel(String id)
    {
        Channel channel;

        synchronized (channels)
        {
            channel = channels.get(id);
        }

        // It seems the channel is still active.
        if (channel != null)
            channel.touch();

        return channel;
    }

    /**
     * Gets the number of <tt>Channel</tt>s of this <tt>Content</tt>.
     *
     * @return the number of <tt>Channel</tt>s of this <tt>Content</tt>
     */
    public int getChannelCount()
    {
        synchronized (channels)
        {
            return channels.size();
        }
    }

    /**
     * Gets the <tt>Channel</tt>s of this <tt>Content</tt>.
     *
     * @return the <tt>Channel</tt>s of this <tt>Content</tt>
     */
    public Channel[] getChannels()
    {
        synchronized (channels)
        {
            Collection<Channel> values = channels.values();

            return values.toArray(new Channel[values.size()]);
        }
    }

    /**
     * Gets the <tt>Conference</tt> which has initialized this <tt>Content</tt>.
     *
     * @return the <tt>Conference</tt> which has initialized this
     * <tt>Content</tt>
     */
    public final Conference getConference()
    {
        return conference;
    }

    /**
     * Returns the local synchronization source identifier (SSRC) associated
     * with this <tt>Content</tt>,
     *
     * @return the local synchronization source identifier (SSRC) associated
     * with this <tt>Content</tt>,
     */
    long getInitialLocalSSRC()
    {
        return initialLocalSSRC;
    }

    /**
     * Gets the time in milliseconds of the last activity related to this
     * <tt>Content</tt>.
     *
     * @return the time in milliseconds of the last activity related to this
     * <tt>Content</tt>
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
     * @return a <tt>MediaService</tt> implementation (if any).
     */
    MediaService getMediaService()
    {
        return getConference().getMediaService();
    }

    /**
     * Gets the <tt>MediaType</tt> of this <tt>Content</tt>. The implementation
     * detects the <tt>MediaType</tt> by looking at the <tt>name</tt> of this
     * instance.
     *
     * @return the <tt>MediaType</tt> of this <tt>Content</tt>
     */
    public MediaType getMediaType()
    {
        return mediaType;
    }

    /**
     * Gets the <tt>MediaDevice</tt> which mixes the media received by the
     * <tt>Channels</tt>  of this <tt>Content</tt> which use a mixer as their
     * RTP-level relay.
     *
     * @return the <tt>MediaDevice</tt> which mixes the media received by the
     * <tt>Channels</tt>  of this <tt>Content</tt> which use a mixer as their
     * RTP-level relay
     */
    public MediaDevice getMixer()
    {
        if (mixer == null)
        {
            MediaType mediaType = getMediaType();
            MediaDevice device;

            if (MediaType.AUDIO.equals(mediaType))
                device = new AudioSilenceMediaDevice();
            else
                device = null;

            if (device == null)
            {
                throw new UnsupportedOperationException(
                        "The mixer type of RTP-level relay is not supported"
                                + " for " + mediaType);
            }
            else
                mixer = getMediaService().createMixer(device);
        }
        return mixer;
    }

    /**
     * Gets the name of this <tt>Content</tt>.
     *
     * @return the name of this <tt>Content</tt>
     */
    public final String getName()
    {
        return name;
    }


    /**
     * Gets the <tt>Recorder</tt> instance used to record media for this
     * <tt>Content</tt>. Creates it, if necessary.
     *
     * TODO: For the moment it is assumed that only RTP translation is used.
     *
     * @return the <tt>Recorder</tt> instance used to record media for this
     * <tt>Content</tt>.
     */
    public Recorder getRecorder()
    {
        if (recorder == null)
        {
            MediaType mediaType = getMediaType();
            if (!MediaType.VIDEO.equals(mediaType)
                    && !MediaType.AUDIO.equals(mediaType))
                return null;

            recorder = getMediaService()
                    .createRecorder(getRTPTranslator());
            recorder.setEventHandler(getConference().getRecorderEventHandler());
        }
        return recorder;
    }

    RTCPFeedbackMessageSender getRTCPFeedbackMessageSender()
    {
        return rtcpFeedbackMessageSender;
    }

    /**
     * Sets the RTCP termination strategy of the <tt>rtpTranslator</tt> to the
     * one specified in the strategyFQN parameter.
     *
     */
    public void setRTCPTerminationStrategyFromFQN(String strategyFQN)
    {
        if (MediaType.VIDEO.equals(mediaType)
                && (strategyFQN != null)
                && ((strategyFQN = strategyFQN.trim()).length() != 0))
        {
            RTPTranslator rtpTranslator = this.rtpTranslator;

            if (rtpTranslator == null)
                return;

            try
            {
                Class<?> clazz = Class.forName(strategyFQN);
                RTCPTerminationStrategy strategy
                    = (RTCPTerminationStrategy) clazz.newInstance();

                if (strategy instanceof BridgeRTCPTerminationStrategy)
                {
                    ((BridgeRTCPTerminationStrategy) strategy)
                            .setConference(this.conference);
                }

                rtpTranslator.setRTCPTerminationStrategy(strategy);
            }
            catch (Exception e)
            {
                logger.error(
                        "Failed to configure the RTCP termination strategy",
                        e);
            }
        }
    }

    /**
     * Sets the RTCP termination strategy of the <tt>rtpTranslator</tt> to the
     * one specified in the configuration.
     *
     */
    public void setRTCPTerminationStrategyFromConfiguration()
    {
        if (!MediaType.VIDEO.equals(mediaType))
        {
            return;
        }

        ConfigurationService cfg = getConference().getVideobridge()
                .getConfigurationService();

        if (cfg != null)
        {
            String strategyFQN = cfg.getString(
                    Videobridge.RTCP_TERMINATION_STRATEGY_PNAME, "");

            setRTCPTerminationStrategyFromFQN(strategyFQN);
        }
    }

    /**
     * Gets the <tt>RTPTranslator</tt> which forwards the RTP and RTCP traffic
     * between the <tt>Channel</tt>s of this <tt>Content</tt> which use a
     * translator as their RTP-level relay.
     *
     * @return the <tt>RTPTranslator</tt> which forwards the RTP and RTCP
     * traffic between the <tt>Channel</tt>s of this <tt>Content</tt> which use
     * a translator as their RTP-level relay
     */
    public RTPTranslator getRTPTranslator()
    {
        synchronized (rtpLevelRelaySyncRoot)
        {
            /*
             * The expired field of Content is initially assigned true and the
             * only possible change of the value is from true to false, never
             * from false to true. Moreover, an existing rtpTranslator will be
             * disposed after the change of expired from true to false.
             * Consequently, no synchronization with respect to the access of
             * expired is required.
             */
            if ((rtpTranslator == null) && !expired)
            {
                rtpTranslator = getMediaService().createRTPTranslator();
                if (rtpTranslator != null)
                {
                    new RTPTranslatorWriteFilter(rtpTranslator, this);
                    if (rtpTranslator instanceof RTPTranslatorImpl)
                    {
                        RTPTranslatorImpl rtpTranslatorImpl
                                = (RTPTranslatorImpl) rtpTranslator;

                        initialLocalSSRC = new Random().nextInt();

                        rtpTranslatorImpl.setLocalSSRC(initialLocalSSRC);

                        MediaType mediaType = getMediaType();
                        if (MediaType.VIDEO.equals(mediaType))
                            setRTCPTerminationStrategyFromConfiguration();

                        rtcpFeedbackMessageSender
                            = rtpTranslatorImpl.getRtcpFeedbackMessageSender();
                    }
                }
            }
            return rtpTranslator;
        }
    }

    /**
     * Returns <tt>SctpConnection</tt> for given <tt>Endpoint</tt>.
     *
     * @param endpoint the <tt>Endpoint</tt> of <tt>SctpConnection</tt> that
     * we're looking for.
     * @return <tt>SctpConnection</tt> for given <tt>Endpoint</tt> if any or
     * <tt>null</tt> otherwise.
     */
    @Deprecated
    public SctpConnection getSctpConnection(Endpoint endpoint)
    {
        if (endpoint == null)
            return null;

        // SCTP connection is bound to an Endpoint just after gets created
        // (in the constructor), so expect to find it there
        return endpoint.getSctpConnection();
    }

    /**
     * Returns <tt>SctpConnection</tt> for given <tt>Endpoint</tt>.
     *
     * @param id the <tt>id</tt> of <tt>SctpConnection</tt> that we're looking
     *           for.
     * @return <tt>SctpConnection</tt> for given <tt>Endpoint</tt> if any or
     *         <tt>null</tt> otherwise.
     */
    public SctpConnection getSctpConnection(String id)
    {
        return (SctpConnection) getChannel(id);
    }

    /**
     * Returns <tt>true</tt> if media recording for this <tt>Content</tt> is
     * currently enabled, and <tt>false</tt> otherwise.
     *
     * @return <tt>true</tt> if media recording for this <tt>Content</tt> is
     * currently enabled, and <tt>false</tt> otherwise.
     */
    public boolean isRecording()
    {
        return recording;
    }

    /**
     * Attempts to enable or disable media recording for this <tt>Content</tt>
     * and updates the recording path.
     *
     * @param recording whether to enable or disable media recording.
     * @param path the path to the directory into which to store files related
     * to media recording for this <tt>Content</tt>.
     *
     * @return the state of the media recording for this <tt>Content</tt>
     * after the attempt to enable (or disable).
     */
    public boolean setRecording(boolean recording, String path)
    {
        this.recordingPath = path;

        if (this.recording != recording)
        {
            Recorder recorder = getRecorder();
            if (recording)
            {
                if (recorder != null)
                {
                    recording = startRecorder(recorder);
                }
                else
                {
                    recording = false;
                }
            }
            else //disable recording
            {
                if (recorder != null)
                {
                    recorder.stop();
                    this.recorder = null;
                }
                recording = false;
            }
        }

        this.recording = recording;
        return this.recording;
    }

    /**
     * Tries to start a specific <tt>Recorder</tt>.
     * @param recorder the <tt>Recorder</tt> to start.
     * @return <tt>true</tt> if <tt>recorder</tt> was started, <tt>false</tt>
     * otherwise.
     */
    private boolean startRecorder(Recorder recorder)
    {
        boolean started = false;
        MediaType mediaType = getMediaType();
        String format = null;
        if (MediaType.AUDIO.equals(mediaType))
            format = "mp3";

        try
        {
            recorder.start(format, recordingPath);
            started = true;
        }
        catch (IOException ioe)
        {
            logger.error("Failed to start recorder: " + ioe);
            started = false;
        }
        catch (MediaException me)
        {
            logger.error("Failed to start recorder: " + me);
            started = false;
        }

        return started;
    }

    /**
     * Sets the time in milliseconds of the last activity related to this
     * <tt>Content</tt> to the current system time.
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


    private static class RTPTranslatorWriteFilter
        implements RTPTranslator.WriteFilter
    {
        private final WeakReference<RTPTranslator> rtpTranslator;

        private final WeakReference<RTPTranslator.WriteFilter> writeFilter;

        public RTPTranslatorWriteFilter(
                RTPTranslator rtpTranslator,
                RTPTranslator.WriteFilter writeFilter)
        {
            this.rtpTranslator
                = new WeakReference<RTPTranslator>(rtpTranslator);
            this.writeFilter
                = new WeakReference<RTPTranslator.WriteFilter>(writeFilter);

            rtpTranslator.addWriteFilter(this);
        }

        @Override
        public boolean accept(
                MediaStream source,
                byte[] buffer, int offset, int length,
                MediaStream destination,
                boolean data)
        {
            RTPTranslator.WriteFilter writeFilter = this.writeFilter.get();
            boolean accept = true;

            if (writeFilter == null)
            {
                RTPTranslator rtpTranslator = this.rtpTranslator.get();

                if (rtpTranslator != null)
                    rtpTranslator.removeWriteFilter(this);
            }
            else
            {
                accept
                    = writeFilter.accept(
                            source,
                            buffer, offset, length,
                            destination,
                            data);
            }
            return accept;
        }
    }
}
