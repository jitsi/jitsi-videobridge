package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.ColibriConferenceIQ;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.RTPLevelRelayType;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.SourcePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.IceUdpTransportPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.PayloadTypePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.RTPHdrExtPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.SourceGroupPacketExtension;
import org.jetbrains.annotations.NotNull;
import org.jitsi.service.neomedia.*;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Localpart;

import java.util.*;

/**
 * The {@link ColibriShim} bridges the COLIBRI world with the new content-less, channel-less world.  It tracks
 * data that is sent/expected in COLIBRI but is no longer directly used.
 */
public class ColibriShim {
    public class ChannelShim
    {
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

        public ChannelShim(
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
            if (expire == null)
            {
                throw new Error("Null expire value for channel " + id);
            }
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

    class SctpConnectionShim extends ChannelShim
    {
        public SctpConnectionShim(
                @NotNull String id,
                @NotNull AbstractEndpoint endpoint)
        {
            super(id, endpoint, false);
        }
    }

    public class ChannelBundleShim {
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

    public class ContentShim {
        private final MediaType type;
        private final Map<String, ChannelShim> channels = new HashMap<>();

        public ContentShim(MediaType type)
        {
            this.type = type;
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
         * @return a new channel ID, unique in the list of this {@link ContentShim}'s
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

        public MediaType getType() {
            return type;
        }


        public ChannelShim createRtpChannel(
                ConferenceShim conference,
                String endpointId,
                boolean isOcto) {
            synchronized (channels)
            {
                String channelId = generateUniqueChannelID();

                ChannelShim channelShim = new ChannelShim(channelId, conference.getOrCreateEndpoint(endpointId), isOcto);
                channels.put(channelId, channelShim);
                return channelShim;
            }
        }

        public SctpConnectionShim createSctpConnection(String conferenceId, String endpointId)
        {
            synchronized (channels)
            {
                AbstractEndpoint endpoint =
                        ColibriShim.this.videobridge.getConference(conferenceId, null).getOrCreateEndpoint(endpointId);
                if (endpoint instanceof Endpoint)
                {
                    String sctpConnId = generateUniqueChannelID();
                    SctpConnectionShim connection = new SctpConnectionShim(sctpConnId, endpoint);
                    channels.put(sctpConnId, connection);

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

        public ChannelShim getChannel(String channelId)
        {
            synchronized (channels)
            {
                return channels.get(channelId);
            }
        }

        public Collection<ChannelShim> getChannels()
        {
            synchronized (channels)
            {
                return new ArrayList<>(channels.values());
            }
        }

        public SctpConnectionShim getSctpConnection(String id)
        {
            synchronized (channels)
            {
                ChannelShim channel = channels.get(id);
                if (channel instanceof SctpConnectionShim)
                {
                    return (SctpConnectionShim)channel;
                }
                return null;
            }
        }

        private void expire()
        {
            synchronized (channels)
            {
                channels.values().forEach(channel -> {
                    channel.setExpire(0);
                });
                channels.clear();
            }
        }
    }
    public class ConferenceShim {
        public final Conference conference;

        private final Map<MediaType, ContentShim> contents = new HashMap<>();
        private final Map<String, ChannelBundleShim> channelBundles = new HashMap<>();

        public ConferenceShim(Jid focus, Localpart name, boolean enableLogging, String confGid)
        {
            this.conference = videobridge.createConference(focus, name, enableLogging, confGid);
            System.out.println("ConferenceShim created conference " + conference.getID());
        }

        public ContentShim getOrCreateContent(MediaType type) {
            synchronized (contents)
            {
                System.out.println("ConferenceShim " + getId() + " creating content " + type);
                return contents.computeIfAbsent(type, key -> new ContentShim(type));
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
            System.out.println("ConferenceShim " + getId() + " creating endpoint " + endpointId);
            return conference.getOrCreateEndpoint(endpointId);
        }

        public ChannelBundleShim getOrCreateChannelBundle(String channelBundleId)
        {
            synchronized (channelBundles)
            {
                return channelBundles.computeIfAbsent(channelBundleId, id -> new ChannelBundleShim(getId(), channelBundleId));
            }
        }

        public ChannelBundleShim getChannelBundle(String channelBundleId)
        {
            synchronized (channelBundles)
            {
                return channelBundles.get(channelBundleId);
            }
        }

        public Collection<AbstractEndpoint> getEndpoints()
        {
            return conference.getEndpoints();
        }

        public boolean shouldExpire()
        {
            return conference.shouldExpire();
        }

        /**
         * Expire this conference and everything within it
         * NOTE: this should only be called from {@link ColibriShim} so that the conference shim can be removed
         * from the list of conferences
         */
        private void expire()
        {
            synchronized (contents)
            {
                contents.values().forEach(ContentShim::expire);
                contents.clear();
            }
            synchronized (channelBundles)
            {
                channelBundles.clear();
            }

            conference.getEndpoints().forEach(AbstractEndpoint::expire);
            conference.safeExpire();
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
    private final Videobridge videobridge;

    public ColibriShim(Videobridge videobridge)
    {
        this.videobridge = videobridge;
    }

    public ConferenceShim createConference(Jid focus, Localpart confName, String confGid) {
        return createConference(focus, confName, true /* enable logging */, confGid);
    }

    public ConferenceShim createConference(Jid focus, Localpart confName, boolean enableLogging, String confGid) {
        synchronized (conferenceShims)
        {
            ConferenceShim conference = new ConferenceShim(focus, confName, enableLogging, confGid);

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

    public void expireConference(String id)
    {
        synchronized (conferenceShims)
        {
            ConferenceShim conference = conferenceShims.remove(id);
            if (conference != null)
            {
                conference.expire();
            }
        }
    }
}
