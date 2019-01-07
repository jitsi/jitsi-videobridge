package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.ColibriConferenceIQ;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.RTPLevelRelayType;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.SourcePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.IceUdpTransportPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.PayloadTypePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.RTPHdrExtPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.SourceGroupPacketExtension;
import org.jetbrains.annotations.NotNull;
import org.jitsi.service.neomedia.MediaDirection;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Localpart;

import java.util.*;

/**
 * The {@link ColibriShim} bridges the COLIBRI world with the new content-less, channel-less world.  It tracks
 * data that is sent/expected in COLIBRI but is no longer directly used.
 */
public class ColibriShim {
    class Channel {
        final String id;
        final AbstractEndpoint endpoint;
        final boolean isOcto;
        Integer lastN = null;
        MediaDirection direction;
        private Collection<PayloadTypePacketExtension> payloadTypes;
        Collection<RTPHdrExtPacketExtension> rtpHeaderExtensions;
        Collection<SourcePacketExtension> sources;
        Collection<SourceGroupPacketExtension> sourceGroups;

        private Integer expire;
        private boolean expired = false;

        public Channel(
                @NotNull String id,
                @NotNull AbstractEndpoint endpoint,
                boolean isOcto)
        {
            this.id = id;
            this.endpoint = endpoint;
            this.isOcto = isOcto;
            endpoint.addChannel(this);
        }

        public Collection<PayloadTypePacketExtension> getPayloadTypes() {
            return payloadTypes;
        }

        public void setPayloadTypes(Collection<PayloadTypePacketExtension> payloadTypes) {
            this.payloadTypes = payloadTypes;
        }

        public Integer getExpire()
        {
            return expire;
        }

        public void setExpire(Integer expire)
        {
            this.expire = expire;
            if (expire == 0)
            {
                expired = true;
                endpoint.removeChannel(this);
            }
        }

        public boolean isExpired()
        {
            return expired;
        }


        public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
        {
            commonIq.setEndpoint(endpoint.getID());
            commonIq.setID(id);
            // I don't think we even support not being the initiator at this point, so hard-coding this
            commonIq.setInitiator(true);
            // Elsewhere we enforce that endpoint ID == channel bundle ID
            commonIq.setChannelBundleId(endpoint.getID());
            if (commonIq instanceof ColibriConferenceIQ.Channel)
            {
                ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;
                iq.setRTPLevelRelayType(RTPLevelRelayType.TRANSLATOR);
                iq.setLastN(lastN);
                iq.setDirection(direction);

                //TODO: initialLocalSsrc/sources.  do we need to set these? when are they used?
            }

            if (isOcto)
            {
                commonIq.setType(ColibriConferenceIQ.OctoChannel.TYPE);
            }
        }
    }

    class SctpConnection extends Channel {
        public SctpConnection(
                @NotNull String id,
                @NotNull AbstractEndpoint endpoint)
        {
            super(id, endpoint, false);
        }
    }

    class ChannelBundleShim {
        private final String bundleId;
        private final IceUdpTransportManager transportManager;

        public ChannelBundleShim(String conferenceId, String bundleId)
        {
            this.bundleId = bundleId;
            // Create the underlying transport manager
            transportManager =
                    ColibriShim.this.videobridge.getConference(conferenceId, null)
                            .getTransportManager(bundleId, true, true);

            // Bundle ID and endpoint ID must be the same
            AbstractEndpoint ep = ColibriShim.this.videobridge.getConference(conferenceId, null).getEndpoint(bundleId);
            if (ep instanceof Endpoint)
            {
                ((Endpoint)ep).setTransportManager(transportManager);
            }
            else
            {
                throw new Error("Tried to set a transport manager on an invalid ep type: " + ep.getClass());
            }

        }

        public void startConnectivityEstablishment(IceUdpTransportPacketExtension transportExtension)
        {
            transportManager.startConnectivityEstablishment(transportExtension);
        }

        public void describe(ColibriConferenceIQ.ChannelBundle bundleIq)
        {
            transportManager.describe(bundleIq);
        }
    }

    class ContentShim {
        private final String name;
        private final Map<String, Channel> channels = new HashMap<>();
        private final Map<String, SctpConnection> sctpConnections = new HashMap<>();

        public ContentShim(String name)
        {
            this.name = name;
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
         * @return a new channel ID, unique in the list of this {@link Content}'s
         * channels.
         */
        private String generateUniqueChannelID()
        {
            synchronized (channels)
            {
                String id;
                do
                {
                    id = generateChannelID();
                }
                while (channels.containsKey(id));

                return id;
            }
        }

        public String getName() {
            return name;
        }


        public ColibriShim.Channel createRtpChannel(
                ConferenceShim conference,
                String endpointId,
                boolean isOcto) {
            synchronized (channels)
            {
                String channelId = generateUniqueChannelID();

                Channel channel = new Channel(channelId, conference.getOrCreateEndpoint(endpointId), isOcto);
                channels.put(channelId, channel);
                return channel;
            }
        }

        public SctpConnection createSctpConnection(String conferenceId, String endpointId)
        {
            synchronized (sctpConnections)
            {
                AbstractEndpoint endpoint =
                        ColibriShim.this.videobridge.getConference(conferenceId, null).getOrCreateEndpoint(endpointId);
                if (endpoint instanceof Endpoint)
                {
                    String sctpConnId = generateUniqueChannelID();
                    SctpConnection connection = new SctpConnection(sctpConnId, endpoint);
                    sctpConnections.put(sctpConnId, connection);

                    // Trigger the creation of the actual new SCTP connection
                    ((Endpoint)endpoint).createSctpConnection();

                    return connection;
                }
                else
                {
                    throw new Error("Tried to create an SCTP connection on invalid ep type: " + endpoint.getClass());
                }
            }
        }

        public ColibriShim.Channel getChannel(String channelId)
        {
            synchronized (channels)
            {
                return channels.get(channelId);
            }
        }

        public Collection<ColibriShim.Channel> getChannels()
        {
            synchronized (channels)
            {
                return new ArrayList<Channel>(channels.values());
            }
        }

        public ColibriShim.SctpConnection getSctpConnection(String id)
        {
            synchronized (sctpConnections)
            {
                return sctpConnections.get(id);
            }
        }
    }
    class ConferenceShim {
        final Conference conference;

        // name -> ContentShim
        private final Map<String, ContentShim> contents = new HashMap<>();
        private final Map<String, ChannelBundleShim> channelBundles = new HashMap<>();

        public ConferenceShim(Jid focus, Localpart name, String confGid)
        {
            this.conference = videobridge.createConference(focus, name, confGid);
        }

        public ContentShim getOrCreateContent(String name) {
            synchronized (contents)
            {
                return contents.computeIfAbsent(name, key -> new ContentShim(name));
            }
        }

        public Collection<ContentShim> getContents()
        {
            synchronized (contents)
            {
                return new ArrayList<>(contents.values());
            }
        }

        public AbstractEndpoint getOrCreateEndpoint(String endpointId)
        {
            return conference.getOrCreateEndpoint(endpointId);
        }

        public ChannelBundleShim getOrCreateChannelBundle(String channelBundleId)
        {
            synchronized (channelBundles)
            {
                return channelBundles.computeIfAbsent(channelBundleId, id -> new ChannelBundleShim(getId(), channelBundleId));
            }
        }

        public void describeChannelBundles(ColibriConferenceIQ iq, Set<String> channelBundleIdsToDescribe)
        {
            synchronized (channelBundles)
            {
                channelBundleIdsToDescribe.forEach(bundleId -> {
                    ChannelBundleShim channelBundle = channelBundles.get(bundleId);
                    if (channelBundle != null)
                    {
                        ColibriConferenceIQ.ChannelBundle responseBundleIQ
                                = new ColibriConferenceIQ.ChannelBundle(bundleId);
                        channelBundle.describe(responseBundleIQ);

                        iq.addChannelBundle(responseBundleIQ);
                    }
                });
            }
        }

        public void describeEndpoints(ColibriConferenceIQ iq)
        {
            conference.describeEndpoints(iq);
        }

        public void describeShallow(ColibriConferenceIQ iq)
        {
            iq.setID(conference.getID());
            iq.setName(conference.getName());
        }

        public String getId()
        {
            return conference.getID();
        }
    }


    private final Map<String, ConferenceShim> conferenceShims = new HashMap<>();
    private static final Random RANDOM = new Random();
    private final Videobridge videobridge;

    /**
     * Generates a new <tt>Conference</tt> ID which is not guaranteed to be
     * unique.
     *
     * @return a new <tt>Conference</tt> ID which is not guaranteed to be unique
     */
    private String generateConferenceID()
    {
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

    private String generateUniqueConferenceId()
    {
        synchronized (conferenceShims)
        {
            String id;
            do
            {
                id = generateConferenceID();
            }
            while (conferenceShims.containsKey(id));

            return id;
        }
    }

    public ColibriShim(Videobridge videobridge)
    {
        this.videobridge = videobridge;
    }

    public ConferenceShim createConference(Jid focus, Localpart confName, String confGid) {
        synchronized (conferenceShims)
        {
            ConferenceShim conference = new ConferenceShim(focus, confName, confGid);

            conferenceShims.put(conference.getId(), conference);

            videobridge.createConference(focus, confName, confGid);

            return conference;
        }
    }

    public Collection<ConferenceShim> getConferences()
    {
        synchronized (conferenceShims)
        {
            return new ArrayList<>(conferenceShims.values());
        }
    }

    public ConferenceShim getConference(String id)
    {
        synchronized (conferenceShims)
        {
            return conferenceShims.get(id);
        }
    }
}
