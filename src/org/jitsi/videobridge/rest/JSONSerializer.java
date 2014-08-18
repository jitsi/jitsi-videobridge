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
    static final String CANDIDATE_LIST
        = CandidatePacketExtension.ELEMENT_NAME + "s";

    static final String CHANNELS
        = ColibriConferenceIQ.Channel.ELEMENT_NAME + "s";

    static final String CONTENTS
        = ColibriConferenceIQ.Content.ELEMENT_NAME + "s";

    static final String FINGERPRINTS
        = DtlsFingerprintPacketExtension.ELEMENT_NAME + "s";

    static final String PARAMETERS
        = ParameterPacketExtension.ELEMENT_NAME + "s";

    static final String PAYLOAD_TYPES
        = PayloadTypePacketExtension.ELEMENT_NAME + "s";

    static final String SOURCES = SourcePacketExtension.ELEMENT_NAME + "s";

    static final String SOURCE_GROUPS = SourceGroupPacketExtension.ELEMENT_NAME + "s";

    static final String SSRCS
        = ColibriConferenceIQ.Channel.SSRC_ELEMENT_NAME + "s";

    static final String XMLNS = "xmlns";

    static final String RTCPMUX = "rtcp-mux";

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
        JSONObject channelJSONObject;

        if (channel == null)
        {
            channelJSONObject = null;
        }
        else
        {
            MediaDirection direction = channel.getDirection();
            String endpoint = channel.getEndpoint();
            int expire = channel.getExpire();
            String id = channel.getID();
            Boolean initiator = channel.isInitiator();
            Integer lastN = channel.getLastN();
            Integer receivingSimulcastLayer
                    = channel.getReceivingSimulcastLayer();
            List<PayloadTypePacketExtension> payloadTypes
                = channel.getPayloadTypes();
            RTPLevelRelayType rtpLevelRelayType
                = channel.getRTPLevelRelayType();
            List<SourcePacketExtension> sources = channel.getSources();
            List<SourceGroupPacketExtension> sourceGroups = channel.getSourceGroups();
            int[] ssrcs = channel.getSSRCs();
            IceUdpTransportPacketExtension transport = channel.getTransport();

            channelJSONObject = new JSONObject();
            // direction
            if (direction != null)
            {
                /*
                 * The JSON.simple library that is in use at the time of this
                 * writing will fail to encode Enum values as JSON strings so
                 * convert the Enum value to a Java String. 
                 */
                channelJSONObject.put(
                        ColibriConferenceIQ.Channel.DIRECTION_ATTR_NAME,
                        direction.toString());
            }
            // endpoint
            if (endpoint != null)
            {
                channelJSONObject.put(
                        ColibriConferenceIQ.Channel.ENDPOINT_ATTR_NAME,
                        endpoint);
            }
            // expire
            if (expire >= 0)
            {
                channelJSONObject.put(
                        ColibriConferenceIQ.Channel.EXPIRE_ATTR_NAME,
                        expire);
            }
            // id
            if (id != null)
            {
                channelJSONObject.put(
                        ColibriConferenceIQ.Channel.ID_ATTR_NAME,
                        id);
            }
            // initiator
            if (initiator != null)
            {
                channelJSONObject.put(
                        ColibriConferenceIQ.Channel.INITIATOR_ATTR_NAME,
                        initiator);
            }
            // lastN
            if (lastN != null)
            {
                channelJSONObject.put(
                        ColibriConferenceIQ.Channel.LAST_N_ATTR_NAME,
                        lastN);
            }
            // receiving simulcast layer
            if (lastN != null)
            {
                channelJSONObject.put(
                        ColibriConferenceIQ.Channel.RECEIVING_SIMULCAST_LAYER,
                        receivingSimulcastLayer);
            }
            // payloadTypes
            if ((payloadTypes != null) && !payloadTypes.isEmpty())
            {
                channelJSONObject.put(
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
                channelJSONObject.put(
                        ColibriConferenceIQ.Channel
                            .RTP_LEVEL_RELAY_TYPE_ATTR_NAME,
                        rtpLevelRelayType.toString());
            }
            // sources
            if ((sources != null) && !sources.isEmpty())
                channelJSONObject.put(SOURCES, serializeSources(sources));
            // source groups
            if ((sourceGroups != null) && !sourceGroups.isEmpty())
                channelJSONObject.put(SOURCE_GROUPS, serializeSourceGroups(sourceGroups));
            // ssrcs
            if ((ssrcs != null) && (ssrcs.length > 0))
                channelJSONObject.put(SSRCS, serializeSSRCs(ssrcs));
            // transport
            if (transport != null)
            {
                channelJSONObject.put(
                        transport.getElementName(),
                        serializeTransport(transport));
            }
        }
        return channelJSONObject;
    }

    public static JSONArray serializeChannels(
            Collection<ColibriConferenceIQ.Channel> channels)
    {
        JSONArray channelsJSONArray;

        if (channels == null)
        {
            channelsJSONArray = null;
        }
        else
        {
            channelsJSONArray = new JSONArray();
            for (ColibriConferenceIQ.Channel channel : channels)
                channelsJSONArray.add(serializeChannel(channel));
        }
        return channelsJSONArray;
    }

    public static JSONObject serializeConference(ColibriConferenceIQ conference)
    {
        JSONObject conferenceJSONObject;

        if (conference == null)
        {
            conferenceJSONObject = null;
        }
        else
        {
            String id = conference.getID();
            List<ColibriConferenceIQ.Content> contents
                = conference.getContents();

            conferenceJSONObject = new JSONObject();
            // id
            if (id != null)
                conferenceJSONObject.put(ColibriConferenceIQ.ID_ATTR_NAME, id);
            // contents
            if ((contents != null) && !contents.isEmpty())
                conferenceJSONObject.put(CONTENTS, serializeContents(contents));
        }
        return conferenceJSONObject;
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
        JSONObject contentJSONObject;

        if (content == null)
        {
            contentJSONObject = null;
        }
        else
        {
            String name = content.getName();
            List<ColibriConferenceIQ.Channel> channels = content.getChannels();

            contentJSONObject = new JSONObject();
            // name
            if (name != null)
            {
                contentJSONObject.put(
                        ColibriConferenceIQ.Content.NAME_ATTR_NAME,
                        name);
            }
            // channels
            if ((channels != null) && !channels.isEmpty())
                contentJSONObject.put(CHANNELS, serializeChannels(channels));
        }
        return contentJSONObject;
    }

    public static JSONArray serializeContents(
            Collection<ColibriConferenceIQ.Content> contents)
    {
        JSONArray contentsJSONArray;

        if (contents == null)
        {
            contentsJSONArray = null;
        }
        else
        {
            contentsJSONArray = new JSONArray();
            for (ColibriConferenceIQ.Content content : contents)
                contentsJSONArray.add(serializeContent(content));
        }
        return contentsJSONArray;
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

    public static Long serializeSource(SourcePacketExtension source)
    {
        return (source == null) ? null : Long.valueOf(source.getSSRC());
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
        JSONObject transportJSONObject;

        if (transport == null)
        {
            transportJSONObject = null;
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

            transportJSONObject = new JSONObject();
            // xmlns
            if (xmlns != null)
                transportJSONObject.put(XMLNS, xmlns);
            // attributes
            serializeAbstractPacketExtensionAttributes(
                    transport,
                    transportJSONObject);
            // fingerprints
            if ((fingerprints != null) && !fingerprints.isEmpty())
            {
                transportJSONObject.put(
                        FINGERPRINTS,
                        serializeFingerprints(fingerprints));
            }
            // candidateList
            if ((candidateList != null) && !candidateList.isEmpty())
            {
                transportJSONObject.put(
                        CANDIDATE_LIST,
                        serializeCandidates(candidateList));
            }
            // remoteCandidate
            if (remoteCandidate != null)
            {
                transportJSONObject.put(
                        remoteCandidate.getElementName(),
                        serializeCandidate(remoteCandidate));
            }
        }
        return transportJSONObject;
    }

    /** Prevents the initialization of new <tt>JSONSerializer</tt> instances. */
    private JSONSerializer()
    {
    }
}
