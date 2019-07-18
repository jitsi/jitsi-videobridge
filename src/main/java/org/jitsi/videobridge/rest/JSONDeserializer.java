/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.rest;

import java.lang.reflect.*;
import java.net.*;
import java.util.*;

import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.json.simple.*;

/**
 * Implements (utility) functions to deserialize instances of
 * {@link ColibriConferenceIQ} and related classes from JSON instances.
 *
 * @author Lyubomir Marinov
 */
final class JSONDeserializer
{
    /**
     * Deserializes the values of a <tt>JSONObject</tt> which are neither
     * <tt>JSONArray</tt>, nor <tt>JSONObject</tt> into attribute values
     * a <tt>AbstractPacketExtension</tt>.
     *
     * @param jsonObject the <tt>JSONObject</tt> whose values which are neither
     * <tt>JSONArray</tt>, nor <tt>JSONObject</tt> to deserialize into attribute
     * values of <tt>abstractPacketExtension</tt>
     * @param abstractPacketExtension the <tt>AbstractPacketExtension</tt> in
     * the attributes of which the values of <tt>jsonObject</tt> which are
     * neither <tt>JSONObject</tt>, nor <tt>JSONArray</tt> are to be
     * deserialized
     */
    public static void deserializeAbstractPacketExtensionAttributes(
            JSONObject jsonObject,
            AbstractPacketExtension abstractPacketExtension)
    {

        for (Map.Entry<Object, Object> e : (Iterable<Map.Entry<Object,
            Object>>) jsonObject
            .entrySet())
        {
            Object key = e.getKey();

            if (key != null)
            {
                String name = key.toString();

                if (name != null)
                {
                    Object value = e.getValue();

                    if (!(value instanceof JSONObject)
                        && !(value instanceof JSONArray))
                    {
                        abstractPacketExtension.setAttribute(name, value);
                    }
                }
            }
        }
    }

    public static <T extends CandidatePacketExtension> T deserializeCandidate(
            JSONObject candidate,
            Class<T> candidateIQClass,
            IceUdpTransportPacketExtension transportIQ)
    {
        T candidateIQ;

        if (candidate == null)
        {
            candidateIQ = null;
        }
        else
        {
            try
            {
                candidateIQ = candidateIQClass.newInstance();
            }
            catch (IllegalAccessException | InstantiationException iae)
            {
                throw new UndeclaredThrowableException(iae);
            }
            // attributes
            deserializeAbstractPacketExtensionAttributes(
                    candidate,
                    candidateIQ);

            transportIQ.addChildExtension(candidateIQ);
        }
        return candidateIQ;
    }

    public static void deserializeCandidates(
            JSONArray candidates,
            IceUdpTransportPacketExtension transportIQ)
    {
        if ((candidates != null) && !candidates.isEmpty())
        {
            for (Object candidate : candidates)
            {
                deserializeCandidate(
                        (JSONObject) candidate,
                        CandidatePacketExtension.class,
                        transportIQ);
            }
        }
    }

    public static ColibriConferenceIQ.Channel deserializeChannel(
            JSONObject channel,
            ColibriConferenceIQ.Content contentIQ)
    {
        ColibriConferenceIQ.Channel channelIQ;

        if (channel == null)
        {
            channelIQ = null;
        }
        else
        {
            Object direction
                = channel.get(ColibriConferenceIQ.Channel.DIRECTION_ATTR_NAME);
            Object lastN
                = channel.get(ColibriConferenceIQ.Channel.LAST_N_ATTR_NAME);
            Object receivingSimulcastStream
                = channel.get(
                        ColibriConferenceIQ.Channel.RECEIVING_SIMULCAST_LAYER);
            Object payloadTypes = channel.get(JSONSerializer.PAYLOAD_TYPES);
            Object rtpLevelRelayType
                = channel.get(
                        ColibriConferenceIQ.Channel
                            .RTP_LEVEL_RELAY_TYPE_ATTR_NAME);
            Object sources = channel.get(JSONSerializer.SOURCES);
            Object sourceGroups = channel.get(JSONSerializer.SOURCE_GROUPS);
            Object ssrcs = channel.get(JSONSerializer.SSRCS);
            Object headerExtensions = channel.get(JSONSerializer.RTP_HEADER_EXTS);

            channelIQ = new ColibriConferenceIQ.Channel();
            deserializeChannelCommon(channel, channelIQ);

            // direction
            if (direction != null)
            {
                channelIQ.setDirection(direction.toString());
            }
            // lastN
            if (lastN != null)
            {
                channelIQ.setLastN(objectToInteger(lastN));
            }
            // receivingSimulcastStream
            if (receivingSimulcastStream != null)
            {
                channelIQ.setReceivingSimulcastLayer(
                    objectToInteger(receivingSimulcastStream));
            }
            // payloadTypes
            if (payloadTypes != null)
            {
                deserializePayloadTypes((JSONArray) payloadTypes, channelIQ);
            }
            // rtpLevelRelayType
            if (rtpLevelRelayType != null)
            {
                channelIQ.setRTPLevelRelayType(rtpLevelRelayType.toString());
            }
            // sources
            if (sources != null)
            {
                deserializeSources((JSONArray) sources, channelIQ);
            }
            // source groups
            if (sourceGroups != null)
            {
                deserializeSourceGroups((JSONArray) sourceGroups, channelIQ);
            }
            // ssrcs
            if (ssrcs != null)
            {
                deserializeSSRCs((JSONArray) ssrcs, channelIQ);
            }
            // header extensions
            if (headerExtensions != null)
            {
                deserializeHeaderExtensions(
                        (JSONArray) headerExtensions,
                        channelIQ);
            }

            contentIQ.addChannel(channelIQ);
        }
        return channelIQ;
    }

    private static Integer objectToInteger(Object o)
    {
        Integer i;

        if (o instanceof Integer)
        {
            i = (Integer) o;
        }
        else if (o instanceof Number)
        {
            i = ((Number) o).intValue();
        }
        else
        {
            i = Integer.valueOf(o.toString());
        }

        return i;
    }

    private static Boolean objectToBoolean(Object o)
    {
        if (o instanceof Boolean)
        {
            return (Boolean) o;
        }
        else
        {
            return Boolean.valueOf(o.toString());
        }
    }

    public static ColibriConferenceIQ.ChannelBundle deserializeChannelBundle(
            JSONObject channelBundle,
            ColibriConferenceIQ conferenceIQ)
    {
        ColibriConferenceIQ.ChannelBundle channelBundleIQ;

        if (channelBundle == null)
        {
            channelBundleIQ = null;
        }
        else
        {
            Object id
                = channelBundle.get(
                        ColibriConferenceIQ.ChannelBundle.ID_ATTR_NAME);
            Object transport
                = channelBundle.get(
                        IceUdpTransportPacketExtension.ELEMENT_NAME);

            channelBundleIQ
                = new ColibriConferenceIQ.ChannelBundle(
                        (id == null) ? null : id.toString());
            // transport
            if (transport != null)
            {
                deserializeTransport((JSONObject) transport, channelBundleIQ);
            }

            conferenceIQ.addChannelBundle(channelBundleIQ);
        }
        return channelBundleIQ;
    }

    public static ColibriConferenceIQ.Endpoint deserializeEndpoint(
            JSONObject endpoint,
            ColibriConferenceIQ conferenceIQ)
    {
        ColibriConferenceIQ.Endpoint endpointIQ;

        if (endpoint == null)
        {
            endpointIQ = null;
        }
        else
        {
            Object id
                = endpoint.get(ColibriConferenceIQ.Endpoint.ID_ATTR_NAME);
            Object statsId
                = endpoint.get(ColibriConferenceIQ.Endpoint.STATS_ID_ATTR_NAME);
            Object displayName
                = endpoint.get(
                    ColibriConferenceIQ.Endpoint.DISPLAYNAME_ATTR_NAME);


            endpointIQ
                = new ColibriConferenceIQ.Endpoint(
                        Objects.toString(id, null),
                        Objects.toString(statsId, null),
                        Objects.toString(displayName, null));

            conferenceIQ.addEndpoint(endpointIQ);
        }
        return endpointIQ;
    }

    public static void deserializeChannelBundles(
            JSONArray channelBundles,
            ColibriConferenceIQ conferenceIQ)
    {
        if ((channelBundles != null) && !channelBundles.isEmpty())
        {
            for (Object channelBundle : channelBundles)
            {
                deserializeChannelBundle(
                        (JSONObject) channelBundle,
                        conferenceIQ);
            }
        }
    }

    public static void deserializeEndpoints(
            JSONArray endpoints,
            ColibriConferenceIQ conferenceIQ)
    {
        if ((endpoints != null) && !endpoints.isEmpty())
        {
            for (Object endpoint : endpoints)
            {
                deserializeEndpoint(
                        (JSONObject) endpoint,
                        conferenceIQ);
            }
        }
    }

    public static void deserializeChannelCommon(
            JSONObject channel,
            ColibriConferenceIQ.ChannelCommon channelIQ)
    {
        Object id = channel.get(ColibriConferenceIQ.Channel.ID_ATTR_NAME);
        Object channelBundleId
            = channel.get(
                    ColibriConferenceIQ.ChannelCommon
                            .CHANNEL_BUNDLE_ID_ATTR_NAME);
        Object endpoint
            = channel.get(ColibriConferenceIQ.ChannelCommon.ENDPOINT_ATTR_NAME);
        Object expire
            = channel.get(ColibriConferenceIQ.ChannelCommon.EXPIRE_ATTR_NAME);
        Object initiator
            = channel.get(
                    ColibriConferenceIQ.ChannelCommon.INITIATOR_ATTR_NAME);
        Object transport
            = channel.get(IceUdpTransportPacketExtension.ELEMENT_NAME);

        // id
        if (id != null)
        {
            channelIQ.setID(id.toString());
        }
        // channelBundleId
        if (channelBundleId != null)
        {
            channelIQ.setChannelBundleId(channelBundleId.toString());
        }
        // endpoint
        if (endpoint != null)
        {
            channelIQ.setEndpoint(endpoint.toString());
        }
        // expire
        if (expire != null)
        {
            int i = objectToInteger(expire);

            if (i != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
            {
                channelIQ.setExpire(i);
            }
        }
        // initiator
        if (initiator != null)
        {
            channelIQ.setInitiator(objectToBoolean(initiator));
        }
        // transport
        if (transport != null)
        {
            deserializeTransport((JSONObject) transport, channelIQ);
        }
    }

    public static void deserializeChannels(
            JSONArray channels,
            ColibriConferenceIQ.Content contentIQ)
    {
        if ((channels != null) && !channels.isEmpty())
        {
            for (Object channel : channels)
            {
                deserializeChannel((JSONObject) channel, contentIQ);
            }
        }
    }

    public static ColibriConferenceIQ deserializeConference(
            JSONObject conference)
    {
        ColibriConferenceIQ conferenceIQ;

        if (conference == null)
        {
            conferenceIQ = null;
        }
        else
        {
            Object id = conference.get(ColibriConferenceIQ.ID_ATTR_NAME);
            Object contents = conference.get(JSONSerializer.CONTENTS);
            Object channelBundles
                = conference.get(JSONSerializer.CHANNEL_BUNDLES);
            Object endpoints
                = conference.get(JSONSerializer.ENDPOINTS);
            Object strategy
                = conference.get(ColibriConferenceIQ
                        .RTCPTerminationStrategy.ELEMENT_NAME);
            Object shutdownExt
                = conference.get(ColibriConferenceIQ
                        .GracefulShutdown.ELEMENT_NAME);

            conferenceIQ = new ColibriConferenceIQ();
            // id
            if (id != null)
            {
                conferenceIQ.setID(id.toString());
            }
            // contents
            if (contents != null)
            {
                deserializeContents((JSONArray) contents, conferenceIQ);
            }
            // channelBundles
            if (channelBundles != null)
            {
                deserializeChannelBundles(
                        (JSONArray) channelBundles,
                        conferenceIQ);
            }
            // endpoints
            if (endpoints != null)
            {
                deserializeEndpoints(
                        (JSONArray) endpoints,
                        conferenceIQ);
            }
            if (strategy != null)
            {
                deserializeRTCPTerminationStrategy(
                    (JSONObject) strategy, conferenceIQ);
            }
            if (shutdownExt != null)
            {
                conferenceIQ.setGracefulShutdown(true);
            }
        }
        return conferenceIQ;
    }

    private static void deserializeRTCPTerminationStrategy(
            JSONObject strategy, ColibriConferenceIQ conferenceIQ)
    {
        if (strategy != null & conferenceIQ != null)
        {
            Object attrName
                    = strategy.get(ColibriConferenceIQ
                        .RTCPTerminationStrategy.NAME_ATTR_NAME);

            String name = Objects.toString(attrName, null);
            if (StringUtils.isNullOrEmpty(name))
            {
                return;
            }

            ColibriConferenceIQ.RTCPTerminationStrategy strategyIQ
                    = new ColibriConferenceIQ.RTCPTerminationStrategy();

            strategyIQ.setName(name);

            conferenceIQ.setRTCPTerminationStrategy(strategyIQ);
        }
    }

    public static ColibriConferenceIQ.Content deserializeContent(
            JSONObject content,
            ColibriConferenceIQ conferenceIQ)
    {
        ColibriConferenceIQ.Content contentIQ;

        if (content == null)
        {
            contentIQ = null;
        }
        else
        {
            Object name
                = content.get(ColibriConferenceIQ.Content.NAME_ATTR_NAME);
            Object channels = content.get(JSONSerializer.CHANNELS);
            Object sctpConnections
                = content.get(JSONSerializer.SCTP_CONNECTIONS);

            contentIQ
                = conferenceIQ.getOrCreateContent(Objects.toString(name, null));
            // channels
            if (channels != null)
            {
                deserializeChannels((JSONArray) channels, contentIQ);
            }
            // sctpConnections
            if (sctpConnections != null)
            {
                deserializeSctpConnections(
                        (JSONArray) sctpConnections,
                        contentIQ);
            }

            conferenceIQ.addContent(contentIQ);
        }
        return contentIQ;
    }

    public static void deserializeContents(
            JSONArray contents,
            ColibriConferenceIQ conferenceIQ)
    {
        if ((contents != null) && !contents.isEmpty())
        {
            for (Object content : contents)
            {
                deserializeContent((JSONObject) content, conferenceIQ);
            }
        }
    }

    public static DtlsFingerprintPacketExtension deserializeFingerprint(
            JSONObject fingerprint,
            IceUdpTransportPacketExtension transportIQ)
    {
        DtlsFingerprintPacketExtension fingerprintIQ;

        if (fingerprint == null)
        {
            fingerprintIQ = null;
        }
        else
        {
            Object theFingerprint
                = fingerprint.get(DtlsFingerprintPacketExtension.ELEMENT_NAME);

            fingerprintIQ = new DtlsFingerprintPacketExtension();
            // fingerprint
            if (theFingerprint != null)
            {
                fingerprintIQ.setFingerprint(theFingerprint.toString());
            }
            // attributes
            deserializeAbstractPacketExtensionAttributes(
                    fingerprint,
                    fingerprintIQ);
            /*
             * XXX The fingerprint is stored as the text of the
             * DtlsFingerprintPacketExtension instance. But it is a Java String
             * and, consequently, the
             * deserializeAbstractPacketExtensionAttributes method will
             * deserialize it into an attribute of the
             * DtlsFingerprintPacketExtension instance.
             */
            fingerprintIQ.removeAttribute(
                    DtlsFingerprintPacketExtension.ELEMENT_NAME);

            transportIQ.addChildExtension(fingerprintIQ);
        }
        return fingerprintIQ;
    }

    public static void deserializeFingerprints(
            JSONArray fingerprints,
            IceUdpTransportPacketExtension transportIQ)
    {
        if ((fingerprints != null) && !fingerprints.isEmpty())
        {
            for (Object fingerprint : fingerprints)
            {
                deserializeFingerprint((JSONObject) fingerprint, transportIQ);
            }
        }
    }

    public static void deserializeParameters(
            JSONObject parameters,
            PayloadTypePacketExtension payloadTypeIQ)
    {
        if (parameters != null)
        {

            for (Map.Entry<Object, Object> e
                        : (Iterable<Map.Entry<Object, Object>>) parameters
                                .entrySet())
            {
                Object name = e.getKey();
                Object value = e.getValue();

                if ((name != null) || (value != null))
                {
                    payloadTypeIQ.addParameter(
                            new ParameterPacketExtension(
                                    Objects.toString(name, null),
                                    Objects.toString(value, null)));
                }
            }
        }
    }

    public static void deserializeRtcpFbs(
            JSONArray rtcpFbs,
            PayloadTypePacketExtension payloadTypeIQ)
    {
        if (rtcpFbs != null)
        {
            for (Object iter : rtcpFbs)
            {
                JSONObject rtcpFb = (JSONObject) iter;
                String type = (String)
                        rtcpFb.get(RtcpFbPacketExtension.TYPE_ATTR_NAME);
                String subtype = (String)
                        rtcpFb.get(RtcpFbPacketExtension.SUBTYPE_ATTR_NAME);
                if (type != null)
                {
                    RtcpFbPacketExtension ext = new RtcpFbPacketExtension();
                    ext.setFeedbackType(type);
                    if (subtype != null)
                    {
                        ext.setFeedbackSubtype(subtype);
                    }
                    payloadTypeIQ.addRtcpFeedbackType(ext);
                }
            }
        }
    }

    public static RTPHdrExtPacketExtension deserializeHeaderExtension(
            JSONObject headerExtension,
            ColibriConferenceIQ.Channel channelIQ)
    {
        RTPHdrExtPacketExtension headerExtensionIQ;
        if (headerExtension == null)
        {
            headerExtensionIQ = null;
        }
        else
        {
            Long id = (Long)headerExtension.get(RTPHdrExtPacketExtension.ID_ATTR_NAME);
            String uriString = (String)headerExtension.get(RTPHdrExtPacketExtension.URI_ATTR_NAME);
            URI uri;
            try
            {
                uri = new URI(uriString);
            }
            catch (URISyntaxException e)
            {
                uri = null;
            }
            if (uri != null)
            {
                headerExtensionIQ = new RTPHdrExtPacketExtension();
                headerExtensionIQ.setID(String.valueOf(id));
                headerExtensionIQ.setURI(uri);
                channelIQ.addRtpHeaderExtension(headerExtensionIQ);
            }
            else
            {
                headerExtensionIQ = null;
            }
        }
        return headerExtensionIQ;
    }

    public static void deserializeHeaderExtensions(
        JSONArray headerExtensions,
        ColibriConferenceIQ.Channel channelIQ)
    {
        if ((headerExtensions != null) && !headerExtensions.isEmpty())
        {
            for (Object headerExtension : headerExtensions)
            {
                deserializeHeaderExtension((JSONObject) headerExtension, channelIQ);
            }
        }
    }


    public static PayloadTypePacketExtension deserializePayloadType(
            JSONObject payloadType,
            ColibriConferenceIQ.Channel channelIQ)
    {
        PayloadTypePacketExtension payloadTypeIQ;

        if (payloadType == null)
        {
            payloadTypeIQ = null;
        }
        else
        {
            Object parameters = payloadType.get(JSONSerializer.PARAMETERS);

            payloadTypeIQ = new PayloadTypePacketExtension();
            // attributes
            deserializeAbstractPacketExtensionAttributes(
                    payloadType,
                    payloadTypeIQ);
            // parameters
            if (parameters != null)
            {
                deserializeParameters((JSONObject) parameters, payloadTypeIQ);
            }

            Object rtcpFbs = payloadType.get(JSONSerializer.RTCP_FBS);

            if (rtcpFbs != null && rtcpFbs instanceof JSONArray) {
                deserializeRtcpFbs((JSONArray) rtcpFbs, payloadTypeIQ);
            }

            channelIQ.addPayloadType(payloadTypeIQ);
        }
        return payloadTypeIQ;
    }

    public static void deserializePayloadTypes(
            JSONArray payloadTypes,
            ColibriConferenceIQ.Channel channelIQ)
    {
        if ((payloadTypes != null) && !payloadTypes.isEmpty())
        {
            for (Object payloadType : payloadTypes)
            {
                deserializePayloadType((JSONObject) payloadType, channelIQ);
            }
        }
    }

    public static ColibriConferenceIQ.SctpConnection deserializeSctpConnection(
            JSONObject sctpConnection,
            ColibriConferenceIQ.Content contentIQ)
    {
        ColibriConferenceIQ.SctpConnection sctpConnectionIQ;

        if (sctpConnection == null)
        {
            sctpConnectionIQ = null;
        }
        else
        {
            Object port
                = sctpConnection.get(
                        ColibriConferenceIQ.SctpConnection.PORT_ATTR_NAME);

            sctpConnectionIQ = new ColibriConferenceIQ.SctpConnection();
            deserializeChannelCommon(sctpConnection, sctpConnectionIQ);

            // port
            if (port != null)
            {
                sctpConnectionIQ.setPort(objectToInteger(port));
            }

            contentIQ.addSctpConnection(sctpConnectionIQ);
        }
        return sctpConnectionIQ;
    }

    public static void deserializeSctpConnections(
            JSONArray sctpConnections,
            ColibriConferenceIQ.Content contentIQ)
    {
        if ((sctpConnections != null) && !sctpConnections.isEmpty())
        {
            for (Object sctpConnection : sctpConnections)
            {
                deserializeSctpConnection(
                        (JSONObject) sctpConnection,
                        contentIQ);
            }
        }
    }

    public static ShutdownIQ deserializeShutdownIQ(
        JSONObject requestJSONObject)
    {
        String element = (String) requestJSONObject.keySet().iterator().next();

        return ShutdownIQ.isValidElementName(element) ?
            ShutdownIQ.createShutdownIQ(element) : null;
    }

    public static SourcePacketExtension deserializeSource(
            Object source)
    {
        SourcePacketExtension sourceIQ;

        if (source == null)
        {
            sourceIQ = null;
        }
        else
        {
            long ssrc;

            try
            {
                ssrc = deserializeSSRC(source);
            }
            catch (NumberFormatException nfe)
            {
                ssrc = -1;
            }
            if (ssrc == -1)
            {
                sourceIQ = null;
            }
            else
            {
                sourceIQ = new SourcePacketExtension();
                sourceIQ.setSSRC(ssrc);
            }
        }
        return sourceIQ;
    }

    public static SourcePacketExtension deserializeSource(
            Object source,
            ColibriConferenceIQ.Channel channelIQ)
    {
        SourcePacketExtension sourcePacketExtension
                = deserializeSource(source);

        if (sourcePacketExtension != null)
        {
            channelIQ.addSource(sourcePacketExtension);
        }

        return sourcePacketExtension;
    }

    public static SourceGroupPacketExtension deserializeSourceGroup(
            Object sourceGroup,
            ColibriConferenceIQ.Channel channelIQ)
    {
        SourceGroupPacketExtension sourceGroupIQ;

        if (sourceGroup == null || !(sourceGroup instanceof JSONObject))
        {
            sourceGroupIQ = null;
        }
        else
        {
            JSONObject sourceGroupJSONObject = (JSONObject) sourceGroup;

            // semantics
            Object semantics = sourceGroupJSONObject
                    .get(SourceGroupPacketExtension.SEMANTICS_ATTR_NAME);

            if (semantics != null
                    && semantics instanceof String
                    && ((String)semantics).length() != 0)
            {
                // ssrcs
                Object sourcesObject = sourceGroupJSONObject
                        .get(JSONSerializer.SOURCES);

                if (sourcesObject != null
                        && sourcesObject instanceof JSONArray
                        && ((JSONArray)sourcesObject).size() != 0)
                {
                    JSONArray sourcesJSONArray = (JSONArray) sourcesObject;
                    List<SourcePacketExtension> sourcePacketExtensions
                        = new ArrayList<>();

                    for (Object source : sourcesJSONArray)
                    {
                        SourcePacketExtension sourcePacketExtension
                                = deserializeSource(source);

                        if (sourcePacketExtension != null)
                        {
                            sourcePacketExtensions.add(sourcePacketExtension);
                        }
                    }

                    sourceGroupIQ = new SourceGroupPacketExtension();
                    sourceGroupIQ.setSemantics(Objects.toString(semantics));
                    sourceGroupIQ.addSources(sourcePacketExtensions);
                    channelIQ.addSourceGroup(sourceGroupIQ);
                }
                else
                {
                    sourceGroupIQ = null;
                }
            }
            else
            {
                sourceGroupIQ = null;
            }
        }
        return sourceGroupIQ;
    }

    public static void deserializeSourceGroups(
            JSONArray sourceGroups,
            ColibriConferenceIQ.Channel channelIQ)
    {
        if ((sourceGroups != null) && !sourceGroups.isEmpty())
        {
            for (Object sourceGroup : sourceGroups)
            {
                deserializeSourceGroup(sourceGroup, channelIQ);
            }
        }
    }

    public static void deserializeSources(
            JSONArray sources,
            ColibriConferenceIQ.Channel channelIQ)
    {
        if ((sources != null) && !sources.isEmpty())
        {
            for (Object source : sources)
            {
                deserializeSource(source, channelIQ);
            }
        }
    }

    public static int deserializeSSRC(Object o)
        throws NumberFormatException
    {
        int i = 0;

        if (o != null)
        {
            if (o instanceof Number)
            {
                i = ((Number) o).intValue();
            }
            else
            {
                String s = o.toString();

                if (s.startsWith("-"))
                {
                    i = Integer.parseInt(s);
                }
                else
                {
                    i = (int) Long.parseLong(s);
                }
            }
        }
        return i;
    }

    public static void deserializeSSRCs(
            JSONArray ssrcs,
            ColibriConferenceIQ.Channel channelIQ)
    {
        if ((ssrcs != null) && !ssrcs.isEmpty())
        {
            for (Object ssrc : ssrcs)
            {
                int ssrcIQ;

                try
                {
                    ssrcIQ = deserializeSSRC(ssrc);
                }
                catch (NumberFormatException nfe)
                {
                    continue;
                }

                channelIQ.addSSRC(ssrcIQ);
            }
        }
    }

    public static IceUdpTransportPacketExtension deserializeTransport(
            JSONObject transport)
    {
        IceUdpTransportPacketExtension transportIQ;

        if (transport == null)
        {
            transportIQ = null;
        }
        else
        {
            Object xmlns = transport.get(JSONSerializer.XMLNS);
            Object fingerprints = transport.get(JSONSerializer.FINGERPRINTS);
            Object candidateList = transport.get(JSONSerializer.CANDIDATE_LIST);
            Object remoteCandidate
                = transport.get(RemoteCandidatePacketExtension.ELEMENT_NAME);
            Object rtcpMux = transport.get(RtcpmuxPacketExtension.ELEMENT_NAME);

            if (IceUdpTransportPacketExtension.NAMESPACE.equals(xmlns))
            {
                transportIQ = new IceUdpTransportPacketExtension();
            }
            else
            {
                transportIQ = null;
            }

            if (transportIQ != null)
            {
                // attributes
                deserializeAbstractPacketExtensionAttributes(
                        transport,
                        transportIQ);
                // fingerprints
                if (fingerprints != null)
                {
                    deserializeFingerprints(
                            (JSONArray) fingerprints,
                            transportIQ);
                }
                // candidateList
                if (candidateList != null)
                {
                    deserializeCandidates(
                            (JSONArray) candidateList,
                            transportIQ);
                }
                // remoteCandidate
                if (remoteCandidate != null)
                {
                    deserializeCandidate(
                            (JSONObject) remoteCandidate,
                            RemoteCandidatePacketExtension.class,
                            transportIQ);
                }
                // rtcpMux
                if (rtcpMux != null && objectToBoolean(rtcpMux))
                {
                    transportIQ.addChildExtension(new RtcpmuxPacketExtension());
                }
            }
        }
        return transportIQ;
    }

    public static IceUdpTransportPacketExtension deserializeTransport(
            JSONObject transport,
            ColibriConferenceIQ.ChannelBundle channelBundleIQ)
    {
        IceUdpTransportPacketExtension transportIQ
            = deserializeTransport(transport);

        if (transportIQ != null)
        {
            channelBundleIQ.setTransport(transportIQ);
        }
        return transportIQ;
    }

    public static IceUdpTransportPacketExtension deserializeTransport(
            JSONObject transport,
            ColibriConferenceIQ.ChannelCommon channelIQ)
    {
        IceUdpTransportPacketExtension transportIQ
            = deserializeTransport(transport);

        if (transportIQ != null)
        {
            channelIQ.setTransport(transportIQ);
        }
        return transportIQ;
    }

    /**
     * Prevents the initialization of new <tt>JSONDeserializer</tt> instances.
     */
    private JSONDeserializer()
    {
    }
}
