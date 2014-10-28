/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rest;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.stats.*;
import org.json.simple.*;

/**
 * Implements (utility) functions to serialize instances of
 * {@link ColibriConferenceIQ} and related classes into JSON instances.
 *
 * @author Lyubomir Marinov
 */
@SuppressWarnings("unchecked")
final class JSONSerializer
{
    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>candidateList</tt> property of
     * <tt>IceUdpTransportPacketExtension</tt>.
     */
    static final String CANDIDATE_LIST
        = CandidatePacketExtension.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>channelBundles</tt> property of <tt>ColibriConferenceIQ</tt>.
     */
    static final String CHANNEL_BUNDLES
        = ColibriConferenceIQ.ChannelBundle.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>channels</tt> property of <tt>ColibriConferenceIQ.Content</tt>.
     */
    static final String CHANNELS
        = ColibriConferenceIQ.Channel.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>contents</tt> property of <tt>ColibriConferenceIQ</tt>.
     */
    static final String CONTENTS
        = ColibriConferenceIQ.Content.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the array of
     * <tt>DtlsFingerprintPacketExtension</tt> child extensions of
     * <tt>IceUdpTransportPacketExtension</tt>.
     */
    static final String FINGERPRINTS
        = DtlsFingerprintPacketExtension.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>parameters</tt> property of <tt>PayloadTypePacketExtension</tt>.
     */
    static final String PARAMETERS
        = ParameterPacketExtension.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>payloadTypes</tt> property of <tt>ColibriConferenceIQ.Channel</tt>.
     */
    static final String PAYLOAD_TYPES
        = PayloadTypePacketExtension.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>sctpConnections</tt> property of
     * <tt>ColibriConferenceIQ.Content</tt>.
     */
    static final String SCTP_CONNECTIONS
        = ColibriConferenceIQ.SctpConnection.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>sourceGroups</tt> property of <tt>ColibriConferenceIQ.Channel</tt>.
     */
    static final String SOURCE_GROUPS
        = SourceGroupPacketExtension.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>sources</tt> property of <tt>ColibriConferenceIQ.Channel</tt>.
     */
    static final String SOURCES = SourcePacketExtension.ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>ssrcs</tt> property of <tt>ColibriConferenceIQ.Channel</tt>.
     */
    static final String SSRCS
        = ColibriConferenceIQ.Channel.SSRC_ELEMENT_NAME + "s";

    /**
     * The name of the JSON pair which specifies the value of the
     * <tt>namespace</tt> property of <tt>IceUdpTransportPacketExtension</tt>.
     */
    static final String XMLNS = "xmlns";

    /**
     * Serializes the attribute values of an <tt>AbstractPacketExtension</tt>
     * into values of a <tt>JSONObject</tt>.
     *
     * @param abstractPacketExtension the <tt>AbstractPacketExtension</tt> whose
     * attribute values are to be serialized into values of <tt>jsonObject</tt>
     * @param jsonObject the <tt>JSONObject</tt> into which the attribute values
     * of <tt>abstractPacketExtension</tt> are to be serialized
     */
    public static void serializeAbstractPacketExtensionAttributes(
            AbstractPacketExtension abstractPacketExtension,
            JSONObject jsonObject)
    {
        for (String name : abstractPacketExtension.getAttributeNames())
        {
            Object value = abstractPacketExtension.getAttribute(name);

            /*
             * The JSON.simple library that is in use at the time of this
             * writing will fail to encode Enum values as JSON strings so
             * convert the Enum value to a Java String. 
             */
            if (value instanceof Enum)
                value = value.toString();

            jsonObject.put(name, value);
        }
    }

    public static JSONObject serializeCandidate(
            CandidatePacketExtension candidate)
    {
        JSONObject candidateJSONObject;

        if (candidate == null)
        {
            candidateJSONObject = null;
        }
        else
        {
            candidateJSONObject = new JSONObject();
            // attributes
            serializeAbstractPacketExtensionAttributes(
                    candidate,
                    candidateJSONObject);
        }
        return candidateJSONObject;
    }

    public static JSONArray serializeCandidates(
            Collection<CandidatePacketExtension> candidates)
    {
        JSONArray candidatesJSONArray;

        if (candidates == null)
        {
            candidatesJSONArray = null;
        }
        else
        {
            candidatesJSONArray = new JSONArray();
            for (CandidatePacketExtension candidate : candidates)
                candidatesJSONArray.add(serializeCandidate(candidate));
        }
        return candidatesJSONArray;
    }

    public static JSONObject serializeChannel(
            ColibriConferenceIQ.Channel channel)
    {
        JSONObject jsonObject;

        if (channel == null)
        {
            jsonObject = null;
        }
        else
        {
            MediaDirection direction = channel.getDirection();
            Integer lastN = channel.getLastN();
            List<PayloadTypePacketExtension> payloadTypes
                = channel.getPayloadTypes();
            Integer receivingSimulcastLayer
                = channel.getReceivingSimulcastLayer();
            RTPLevelRelayType rtpLevelRelayType
                = channel.getRTPLevelRelayType();
            List<SourcePacketExtension> sources = channel.getSources();
            List<SourceGroupPacketExtension> sourceGroups
                = channel.getSourceGroups();
            int[] ssrcs = channel.getSSRCs();

            jsonObject = serializeChannelCommon(channel);
            // direction
            if (direction != null)
            {
                /*
                 * The JSON.simple library that is in use at the time of this
                 * writing will fail to encode Enum values as JSON strings so
                 * convert the Enum value to a Java String. 
                 */
                jsonObject.put(
                        ColibriConferenceIQ.Channel.DIRECTION_ATTR_NAME,
                        direction.toString());
            }
            // lastN
            if (lastN != null)
            {
                jsonObject.put(
                        ColibriConferenceIQ.Channel.LAST_N_ATTR_NAME,
                        lastN);
            }
            // receiving simulcast layer
            if (lastN != null)
            {
                jsonObject.put(
                        ColibriConferenceIQ.Channel.RECEIVING_SIMULCAST_LAYER,
                        receivingSimulcastLayer);
            }
            // payloadTypes
            if ((payloadTypes != null) && !payloadTypes.isEmpty())
            {
                jsonObject.put(
                        PAYLOAD_TYPES,
                        serializePayloadTypes(payloadTypes));
            }
            // rtpLevelRelayType
            if (rtpLevelRelayType != null)
            {
                /*
                 * The JSON.simple library that is in use at the time of this
                 * writing will fail to encode Enum values as JSON strings so
                 * convert the Enum value to a Java String. 
                 */
                jsonObject.put(
                        ColibriConferenceIQ.Channel
                            .RTP_LEVEL_RELAY_TYPE_ATTR_NAME,
                        rtpLevelRelayType.toString());
            }
            // sources
            if ((sources != null) && !sources.isEmpty())
                jsonObject.put(SOURCES, serializeSources(sources));
            // source groups
            if ((sourceGroups != null) && !sourceGroups.isEmpty())
            {
                jsonObject.put(
                        SOURCE_GROUPS,
                        serializeSourceGroups(sourceGroups));
            }
            // ssrcs
            if ((ssrcs != null) && (ssrcs.length > 0))
                jsonObject.put(SSRCS, serializeSSRCs(ssrcs));
        }
        return jsonObject;
    }

    public static JSONObject serializeChannelBundle(
            ColibriConferenceIQ.ChannelBundle channelBundle)
    {
        JSONObject jsonObject;

        if (channelBundle == null)
        {
            jsonObject = null;
        }
        else
        {
            String id = channelBundle.getId();
            IceUdpTransportPacketExtension transport
                = channelBundle.getTransport();

            jsonObject = new JSONObject();
            // id
            if (id != null)
            {
                jsonObject.put(
                        ColibriConferenceIQ.ChannelBundle.ID_ATTR_NAME,
                        id);
            }
            // transport
            if (transport != null)
            {
                jsonObject.put(
                        transport.getElementName(),
                        serializeTransport(transport));
            }
        }
        return jsonObject;
    }

    public static JSONArray serializeChannelBundles(
            Collection<ColibriConferenceIQ.ChannelBundle> channelBundles)
    {
        JSONArray jsonArray;

        if (channelBundles == null)
        {
            jsonArray = null;
        }
        else
        {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.ChannelBundle channelBundle
                    : channelBundles)
            {
                jsonArray.add(serializeChannelBundle(channelBundle));
            }
        }
        return jsonArray;
    }

    public static JSONObject serializeChannelCommon(
            ColibriConferenceIQ.ChannelCommon channelCommon)
    {
        JSONObject jsonObject;

        if (channelCommon == null)
        {
            jsonObject = null;
        }
        else
        {
            String id = channelCommon.getID();
            String channelBundleId = channelCommon.getChannelBundleId();
            String endpoint = channelCommon.getEndpoint();
            int expire = channelCommon.getExpire();
            Boolean initiator = channelCommon.isInitiator();
            IceUdpTransportPacketExtension transport
                = channelCommon.getTransport();

            jsonObject = new JSONObject();
            // id
            if (id != null)
            {
                jsonObject.put(
                        ColibriConferenceIQ.Channel.ID_ATTR_NAME,
                        id);
            }
            // channelBundleId
            if (channelBundleId != null)
            {
                jsonObject.put(
                        ColibriConferenceIQ.ChannelCommon
                                .CHANNEL_BUNDLE_ID_ATTR_NAME,
                        channelBundleId);
            }
            // endpoint
            if (endpoint != null)
            {
                jsonObject.put(
                        ColibriConferenceIQ.ChannelCommon.ENDPOINT_ATTR_NAME,
                        endpoint);
            }
            // expire
            if (expire >= 0)
            {
                jsonObject.put(
                        ColibriConferenceIQ.ChannelCommon.EXPIRE_ATTR_NAME,
                        expire);
            }
            // initiator
            if (initiator != null)
            {
                jsonObject.put(
                        ColibriConferenceIQ.ChannelCommon.INITIATOR_ATTR_NAME,
                        initiator);
            }
            // transport
            if (transport != null)
            {
                jsonObject.put(
                        transport.getElementName(),
                        serializeTransport(transport));
            }
        }
        return jsonObject;
    }

    public static JSONArray serializeChannels(
            Collection<ColibriConferenceIQ.Channel> collection)
    {
        JSONArray jsonArray;

        if (collection == null)
        {
            jsonArray = null;
        }
        else
        {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.Channel element : collection)
                jsonArray.add(serializeChannel(element));
        }
        return jsonArray;
    }

    public static JSONObject serializeConference(ColibriConferenceIQ conference)
    {
        JSONObject jsonObject;

        if (conference == null)
        {
            jsonObject = null;
        }
        else
        {
            String id = conference.getID();
            List<ColibriConferenceIQ.Content> contents
                = conference.getContents();
            List<ColibriConferenceIQ.ChannelBundle> channelBundles
                = conference.getChannelBundles();
            ColibriConferenceIQ.Recording recording = conference.getRecording();

            jsonObject = new JSONObject();
            // id
            if (id != null)
                jsonObject.put(ColibriConferenceIQ.ID_ATTR_NAME, id);
            // contents
            if ((contents != null) && !contents.isEmpty())
                jsonObject.put(CONTENTS, serializeContents(contents));
            // channelBundles
            if ((channelBundles != null) && !channelBundles.isEmpty())
            {
                jsonObject.put(
                        CHANNEL_BUNDLES,
                        serializeChannelBundles(channelBundles));
            }
            // recording
            if (recording != null)
            {
                jsonObject.put(ColibriConferenceIQ.Recording.ELEMENT_NAME,
                               serializeRecording(recording));
            }
        }
        return jsonObject;
    }

    public static JSONArray serializeConferences(
            Collection<ColibriConferenceIQ> conferences)
    {
        JSONArray conferencesJSONArray;

        if (conferences == null)
        {
            conferencesJSONArray = null;
        }
        else
        {
            conferencesJSONArray = new JSONArray();
            for (ColibriConferenceIQ conference : conferences)
                conferencesJSONArray.add(serializeConference(conference));
        }
        return conferencesJSONArray;
    }

    public static JSONObject serializeContent(
            ColibriConferenceIQ.Content content)
    {
        JSONObject jsonObject;

        if (content == null)
        {
            jsonObject = null;
        }
        else
        {
            String name = content.getName();
            List<ColibriConferenceIQ.Channel> channels = content.getChannels();
            List<ColibriConferenceIQ.SctpConnection> sctpConnections
                = content.getSctpConnections();

            jsonObject = new JSONObject();
            // name
            if (name != null)
            {
                jsonObject.put(
                        ColibriConferenceIQ.Content.NAME_ATTR_NAME,
                        name);
            }
            // channels
            if ((channels != null) && !channels.isEmpty())
                jsonObject.put(CHANNELS, serializeChannels(channels));
            // sctpConnections
            if ((sctpConnections != null) && !sctpConnections.isEmpty())
            {
                jsonObject.put(
                        SCTP_CONNECTIONS,
                        serializeSctpConnections(sctpConnections));
            }
        }
        return jsonObject;
    }

    public static JSONArray serializeContents(
            Collection<ColibriConferenceIQ.Content> contents)
    {
        JSONArray jsonArray;

        if (contents == null)
        {
            jsonArray = null;
        }
        else
        {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.Content content : contents)
                jsonArray.add(serializeContent(content));
        }
        return jsonArray;
    }

    public static JSONObject serializeFingerprint(
            DtlsFingerprintPacketExtension fingerprint)
    {
        JSONObject fingerprintJSONObject;

        if (fingerprint == null)
        {
            fingerprintJSONObject = null;
        }
        else
        {
            String theFingerprint = fingerprint.getFingerprint();

            fingerprintJSONObject = new JSONObject();
            // fingerprint
            if (theFingerprint != null)
            {
                fingerprintJSONObject.put(
                        fingerprint.getElementName(),
                        theFingerprint);
            }
            // attributes
            serializeAbstractPacketExtensionAttributes(
                    fingerprint,
                    fingerprintJSONObject);
        }
        return fingerprintJSONObject;
    }

    public static JSONArray serializeFingerprints(
            Collection<DtlsFingerprintPacketExtension> fingerprints)
    {
        JSONArray fingerprintsJSONArray;

        if (fingerprints == null)
        {
            fingerprintsJSONArray = null;
        }
        else
        {
            fingerprintsJSONArray = new JSONArray();
            for (DtlsFingerprintPacketExtension fingerprint : fingerprints)
                fingerprintsJSONArray.add(serializeFingerprint(fingerprint));
        }
        return fingerprintsJSONArray;
    }

    public static JSONObject serializeParameters(
            Collection<ParameterPacketExtension> parameters)
    {
        /*
         * A parameter is a key-value pair and the order of the parameters in a
         * payload-type does not appear to matter so a natural representation of
         * a parameter set is a JSONObject rather than a JSONArray.
         */
        JSONObject parametersJSONObject;

        if (parameters == null)
        {
            parametersJSONObject = null;
        }
        else
        {
            parametersJSONObject = new JSONObject();
            for (ParameterPacketExtension parameter : parameters)
            {
                String name = parameter.getName();
                String value = parameter.getValue();

                if ((name != null) || (value != null))
                    parametersJSONObject.put(name, value);
            }
        }
        return parametersJSONObject;
    }

    public static JSONObject serializePayloadType(
            PayloadTypePacketExtension payloadType)
    {
        JSONObject payloadTypeJSONObject;

        if (payloadType == null)
        {
            payloadTypeJSONObject = null;
        }
        else
        {
            List<ParameterPacketExtension> parameters
                = payloadType.getParameters();

            payloadTypeJSONObject = new JSONObject();
            // attributes
            serializeAbstractPacketExtensionAttributes(
                    payloadType,
                    payloadTypeJSONObject);
            // parameters
            if ((parameters != null) && !parameters.isEmpty())
            {
                payloadTypeJSONObject.put(
                        PARAMETERS,
                        serializeParameters(parameters));
            }
        }
        return payloadTypeJSONObject;
    }

    public static JSONObject serializeRecording(
            ColibriConferenceIQ.Recording recording)
    {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(ColibriConferenceIQ.Recording.STATE_ATTR_NAME,
                       recording.getState());

        String token = recording.getToken();
        if (token != null)
        {
            jsonObject.put(ColibriConferenceIQ.Recording.TOKEN_ATTR_NAME,
                           token);
        }

        String directory = recording.getDirectory();
        if (directory != null)
        {
            jsonObject.put(ColibriConferenceIQ.Recording.DIRECTORY_ATTR_NAME,
                    directory);
        }

        return jsonObject;
    }

    public static JSONArray serializePayloadTypes(
            Collection<PayloadTypePacketExtension> payloadTypes)
    {
        JSONArray payloadTypesJSONArray;

        if (payloadTypes == null)
        {
            payloadTypesJSONArray = null;
        }
        else
        {
            payloadTypesJSONArray = new JSONArray();
            for (PayloadTypePacketExtension payloadType : payloadTypes)
                payloadTypesJSONArray.add(serializePayloadType(payloadType));
        }
        return payloadTypesJSONArray;
    }

    public static JSONObject serializeSctpConnection(
            ColibriConferenceIQ.SctpConnection sctpConnection)
    {
        JSONObject jsonObject;

        if (sctpConnection == null)
        {
            jsonObject = null;
        }
        else
        {
            int port = sctpConnection.getPort();

            jsonObject = serializeChannelCommon(sctpConnection);
            // port
            jsonObject.put(
                    ColibriConferenceIQ.SctpConnection.PORT_ATTR_NAME,
                    Integer.valueOf(port));
        }
        return jsonObject;
    }

    public static JSONArray serializeSctpConnections(
            Collection<ColibriConferenceIQ.SctpConnection> collection)
    {
        JSONArray jsonArray;

        if (collection == null)
        {
            jsonArray = null;
        }
        else
        {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.SctpConnection element : collection)
                jsonArray.add(serializeSctpConnection(element));
        }
        return jsonArray;
    }

    public static Long serializeSource(SourcePacketExtension source)
    {
        return (source == null) ? null : Long.valueOf(source.getSSRC());
    }

    private static Object serializeSourceGroup(
            SourceGroupPacketExtension sourceGroup)
    {
        if (sourceGroup.getSemantics() != null
                && sourceGroup.getSemantics().length() != 0
                && sourceGroup.getSources() != null
                && sourceGroup.getSources().size() != 0)
        {
            JSONObject sourceGroupJSONObject = new JSONObject();

            // Add semantics
            sourceGroupJSONObject.put(
                    SourceGroupPacketExtension.SEMANTICS_ATTR_NAME,
                    JSONValue.escape(sourceGroup.getSemantics()));

            // Add sources
            JSONArray ssrcsJSONArray = new JSONArray();
            for (SourcePacketExtension source : sourceGroup.getSources())
                ssrcsJSONArray.add(Long.valueOf(source.getSSRC()));

            sourceGroupJSONObject.put(SOURCES, ssrcsJSONArray);

            return sourceGroupJSONObject;
        }
        else
        {
            return null;
        }
    }

    public static JSONArray serializeSourceGroups(
            Collection<SourceGroupPacketExtension> sourceGroups)
    {
        JSONArray sourceGroupsJSONArray;

        if (sourceGroups == null || sourceGroups.size() == 0)
        {
            sourceGroupsJSONArray = null;
        }
        else
        {
            sourceGroupsJSONArray = new JSONArray();
            for (SourceGroupPacketExtension sourceGroup : sourceGroups)
                sourceGroupsJSONArray.add(serializeSourceGroup(sourceGroup));
        }
        return sourceGroupsJSONArray;
    }

    public static JSONArray serializeSources(
            Collection<SourcePacketExtension> sources)
    {
        JSONArray sourcesJSONArray;

        if (sources == null)
        {
            sourcesJSONArray = null;
        }
        else
        {
            sourcesJSONArray = new JSONArray();
            for (SourcePacketExtension source : sources)
                sourcesJSONArray.add(serializeSource(source));
        }
        return sourcesJSONArray;
    }

    public static JSONArray serializeSSRCs(int[] ssrcs)
    {
        JSONArray ssrcsJSONArray;

        if (ssrcs == null)
        {
            ssrcsJSONArray = null;
        }
        else
        {
            ssrcsJSONArray = new JSONArray();
            for (int i = 0; i < ssrcs.length; i++)
                ssrcsJSONArray.add(Long.valueOf(ssrcs[i] & 0xFFFFFFFFL));
        }
        return ssrcsJSONArray;
    }

    public static JSONObject serializeStatistics(Statistics statistics)
    {
        JSONObject statisticsJSONObject;

        if (statistics == null)
            statisticsJSONObject = null;
        else
            statisticsJSONObject = new JSONObject(statistics.getStats());
        return statisticsJSONObject;
    }

    public static JSONObject serializeTransport(
            IceUdpTransportPacketExtension transport)
    {
        JSONObject jsonObject;

        if (transport == null)
        {
            jsonObject = null;
        }
        else
        {
            String xmlns = transport.getNamespace();
            List<DtlsFingerprintPacketExtension> fingerprints
                = transport.getChildExtensionsOfType(
                        DtlsFingerprintPacketExtension.class);
            List<CandidatePacketExtension> candidateList
                = transport.getCandidateList();
            RemoteCandidatePacketExtension remoteCandidate
                = transport.getRemoteCandidate();
            boolean rtcpMux = transport.isRtcpMux();

            jsonObject = new JSONObject();
            // xmlns
            if (xmlns != null)
                jsonObject.put(XMLNS, xmlns);
            // attributes
            serializeAbstractPacketExtensionAttributes(transport, jsonObject);
            // fingerprints
            if ((fingerprints != null) && !fingerprints.isEmpty())
            {
                jsonObject.put(
                        FINGERPRINTS,
                        serializeFingerprints(fingerprints));
            }
            // candidateList
            if ((candidateList != null) && !candidateList.isEmpty())
            {
                jsonObject.put(
                        CANDIDATE_LIST,
                        serializeCandidates(candidateList));
            }
            // remoteCandidate
            if (remoteCandidate != null)
            {
                jsonObject.put(
                        remoteCandidate.getElementName(),
                        serializeCandidate(remoteCandidate));
            }
            // rtcpMux
            if (rtcpMux)
            {
                jsonObject.put(
                        RtcpmuxPacketExtension.ELEMENT_NAME,
                        Boolean.valueOf(rtcpMux));
            }
        }
        return jsonObject;
    }

    /** Prevents the initialization of new <tt>JSONSerializer</tt> instances. */
    private JSONSerializer()
    {
    }
}
