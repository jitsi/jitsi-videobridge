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
package org.jitsi.videobridge.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;

import java.util.*;
import java.util.stream.*;

/**
 * A factory of {@link MediaStreamTrackDesc}s from jingle signaling.
 *
 * @author George Politis
 */
public class MediaStreamTrackFactory
{
    /**
     * The {@link ConfigurationService} to pull configuration options from.
     */
    private static ConfigurationService cfg = LibJitsi.getConfigurationService();

    /**
     * The system property name that for a boolean that's controlling whether or
     * not to enable temporal scalability filtering for VP8.
     */
    public static final String ENABLE_SVC_PNAME = "org.jitsi" +
        ".videobridge.ENABLE_SVC";

    /**
     * The system property name that for a boolean that's controlling whether or
     * not to enable temporal scalability filtering for VP8.
     */
    public static final String ENABLE_VP9_SVC_PNAME = "org.jitsi" +
        ".videobridge.ENABLE_VP9_SVC";

    /**
     * The default number of temporal layers to use for VP8 simulcast.
     *
     * FIXME: hardcoded ugh.. this should be either signaled or somehow included
     * in the RTP stream.
     */
    private static final int VP8_SIMULCAST_TEMPORAL_LAYERS = 3;

    /**
     * The resolution of the base stream when activating simulcast for VP8.
     *
     * FIXME: hardcoded ugh.. this should be either signaled or somehow included
     * in the RTP stream.
     */
    private static final int VP8_SIMULCAST_BASE_LAYER_HEIGHT = 180;

    /**
     * The default number of spatial layers to use for VP9 SVC.
     *
     * FIXME: hardcoded ugh.. this should be either signaled or somehow included
     * in the RTP stream.
     */
    private static final int VP9_SVC_SPATIAL_LAYERS = 3;

    /**
     * The default number of spatial layers to use for VP9 SVC.
     *
     * FIXME: hardcoded ugh.. this should be either signaled or somehow included
     * in the RTP stream.
     */
    private static final int VP9_SVC_TEMPORAL_LAYERS = 3;

    /**
     * A boolean that determines whether to enable support for VP9 SVC. This is
     * experimental and is left disabled by default.
     */
    private static final boolean ENABLE_VP9_SVC
        = cfg.getBoolean(ENABLE_VP9_SVC_PNAME, false);;

    /**
     * A boolean that's controlling whether or not to enable SVC filtering for
     * scalable video codecs.
     */
    private static final boolean ENABLE_SVC
        = cfg.getBoolean(ENABLE_SVC_PNAME, false);

    private static RTPEncodingDesc[] createRTPEncodings(
        MediaStreamTrackDesc track, Long[] primary,
        int spatialLen, int temporalLen, Map<Long, List<Pair<Long, String>>> secondarySsrcs)
    {
        RTPEncodingDesc[] rtpEncodings
            = new RTPEncodingDesc[primary.length * spatialLen * temporalLen];

        // this loop builds a subjective quality index array that looks like
        // this:
        //
        // [s0t0, s0t1, s0t2, s1t0, s1t1, s1t2, s2t0, s2t1, s2t2]
        //
        // The spatial layer is offered either by simulcast (VP8) or spatial
        // scalability (VP9). Exotic cases might do simulcast + spatial
        // scalability.

        //TODO(brian): this is only correct if the highest res is 720p
        int height = VP8_SIMULCAST_BASE_LAYER_HEIGHT;
        for (int streamIdx = 0; streamIdx < primary.length; streamIdx++)
        {
            for (int spatialIdx = 0; spatialIdx < spatialLen; spatialIdx++)
            {
                double frameRate = (double) 30 / (1 << (temporalLen - 1));
                for (int temporalIdx = 0;
                     temporalIdx < temporalLen; temporalIdx++)
                {
                    int idx = qid(streamIdx, spatialIdx, temporalIdx,
                        spatialLen, temporalLen);

                    RTPEncodingDesc[] dependencies;
                    if (spatialIdx > 0 && temporalIdx > 0)
                    {
                        // this layer depends on spatialIdx-1 and temporalIdx-1.
                        dependencies = new RTPEncodingDesc[]{
                            rtpEncodings[
                                qid(streamIdx, spatialIdx, temporalIdx - 1,
                                    spatialLen, temporalLen)],
                            rtpEncodings[
                                qid(streamIdx, spatialIdx - 1, temporalIdx,
                                    spatialLen, temporalLen)]
                        };
                    }
                    else if (spatialIdx > 0)
                    {
                        // this layer depends on spatialIdx-1.
                        dependencies = new RTPEncodingDesc[]
                            {rtpEncodings[
                                qid(streamIdx, spatialIdx - 1, temporalIdx,
                                    spatialLen, temporalLen)]};
                    }
                    else if (temporalIdx > 0)
                    {
                        // this layer depends on temporalIdx-1.
                        dependencies = new RTPEncodingDesc[]
                            {rtpEncodings[
                                qid(streamIdx, spatialIdx, temporalIdx - 1,
                                    spatialLen, temporalLen)]};
                    }
                    else
                    {
                        // this is a base layer without any dependencies.
                        dependencies = null;
                    }

                    int temporalId = temporalLen > 1 ? temporalIdx : -1;
                    int spatialId = spatialLen > 1 ? spatialIdx : -1;

                    rtpEncodings[idx]
                        = new RTPEncodingDesc(track, idx,
                        primary[streamIdx],
                        temporalId, spatialId, height, frameRate, dependencies);
                    List<Pair<Long, String>> ssrcSecondarySsrcs = secondarySsrcs.get(primary[streamIdx]);
                    if (ssrcSecondarySsrcs != null)
                    {
                        ssrcSecondarySsrcs.forEach(ssrcSecondarySsrc -> {
                            rtpEncodings[idx].addSecondarySsrc(ssrcSecondarySsrc.getKey(), ssrcSecondarySsrc.getValue());
                        });
                    }

                    frameRate *= 2;
                }
            }

            height *= 2;
        }
        return rtpEncodings;

    }

    private static SourceGroupPacketExtension getGroup(String semantics, List<SourceGroupPacketExtension> groups)
    {
        return groups.stream()
            .filter(sg -> sg.getSemantics().equalsIgnoreCase(semantics))
            .findFirst()
            .orElse(null);
    }

    /**
     * Given a list of sources and groups, find the primary ssrc.
     * NOTE: this assumes there is only a single 'video source' described
     * by the given sources/groups (the rest must be things like rtx, fec,
     * simulcast sub-streams, etc.)
     * @param sources
     * @param sourceGroups
     * @return
     */
    private static long getPrimarySsrc(List<SourcePacketExtension> sources,
                                       List<SourceGroupPacketExtension> sourceGroups)
    {
        // Based on there only being a single video source, we can discover
        // the primary ssrc by doing the following:
        // 1) check if there is a simulcast group; if there is, then the first
        //    ssrc in that group will be the overall primary
        // 2) if no sim group, check if there is an fec group; if there is, then
        //    the first ssrc in that group will be the overall primary
        // 3) if no fec group, check if there is an rtx group; if there is, then
        //    the first ssrc in that group will be the overall primary
        // 4) if no fec group, then there should only be a single source, total,
        //    which will be the primary
        SourceGroupPacketExtension simGroup =
            getGroup(SourceGroupPacketExtension.SEMANTICS_SIMULCAST, sourceGroups);
        if (simGroup != null)
        {
            return simGroup.getSources().get(0).getSSRC();
        }

        SourceGroupPacketExtension fecGroup =
            getGroup(SourceGroupPacketExtension.SEMANTICS_FEC, sourceGroups);
        if (fecGroup != null)
        {
            return fecGroup.getSources().get(0).getSSRC();
        }

        SourceGroupPacketExtension rtxGroup =
            getGroup(SourceGroupPacketExtension.SEMANTICS_FID, sourceGroups);
        if (rtxGroup != null)
        {
            return rtxGroup.getSources().get(0).getSSRC();
        }

        if (!sources.isEmpty())
        {
            return sources.get(0).getSSRC();
        }
        return -1;
    }

    /**
     * Get the 'secondary' ssrcs for the given primary ssrc.  'Secondary' here
     * is defined as things like rtx or fec ssrcs.
     * @param ssrc
     * @param sourceGroups
     * @return a map of secondary ssrc -> type (rtx, fec, etc.)
     */
    private static Map<Long, String> getSecondarySsrcs(long ssrc, List<SourceGroupPacketExtension> sourceGroups)
    {
        Map<Long, String> secondarySsrcs = new HashMap<>();
        for (SourceGroupPacketExtension sourceGroup : sourceGroups)
        {
            if (sourceGroup.getSemantics().equalsIgnoreCase(SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
            {
                // Simulcast does not fall under the definition of 'secondary'
                // we want here.
                continue;
            }
            long groupPrimarySsrc = sourceGroup.getSources().get(0).getSSRC();
            long groupSecondarySsrc = sourceGroup.getSources().get(1).getSSRC();
            if (groupPrimarySsrc == ssrc)
            {
                secondarySsrcs.put(groupSecondarySsrc, sourceGroup.getSemantics());
            }
        }
        return secondarySsrcs;
    }

    /**
     * Build a map of source ssrc -> a list of secondary ssrc, secondary ssrc type
     * @param ssrcs the ssrcs to get secondary ssrcs for
     * @param sourceGroups the signaled source groups
     * @return map of source ssrc -> a list of secondary ssrc, secondary ssrc type
     */
    private static Map<Long, List<Pair<Long, String>>> getAllSecondarySsrcs(Long[] ssrcs, List<SourceGroupPacketExtension> sourceGroups)
    {
        Map<Long, List<Pair<Long, String>>> allSecondarySsrcs = new HashMap<>();

        for (long ssrc : ssrcs)
        {
            Map<Long, String> secondarySsrcs = getSecondarySsrcs(ssrc, sourceGroups);
            List<Pair<Long, String>> secondarySsrcList = secondarySsrcs.entrySet()
                .stream()
                .map(e -> new Pair<Long, String>(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
            allSecondarySsrcs.put(ssrc, secondarySsrcList);
        }
        return allSecondarySsrcs;
    }

    /**
     * Creates {@link MediaStreamTrackDesc}s from signaling params
     *
     * @param mediaStreamTrackReceiver the {@link MediaStreamTrackReceiver} that
     * will receive the created {@link MediaStreamTrackDesc}s.
     * @param sources The {@link List} of {@link SourcePacketExtension} that
     * describes the list of jingle sources.
     * @param sourceGroups The {@link List} of
     * {@link SourceGroupPacketExtension} that describes the list of jingle
     * source groups.
     * @return the {@link MediaStreamTrackReceiver} that are described in the
     * jingle sources and source groups.
     */
    public static MediaStreamTrackDesc[] createMediaStreamTracks(
        MediaStreamTrackReceiver mediaStreamTrackReceiver,
        List<SourcePacketExtension> sources,
        List<SourceGroupPacketExtension> sourceGroups)
    {
        // Currently we only support a single track, so get the single
        // primary ssrc
        long primarySsrc = getPrimarySsrc(sources, sourceGroups);
        if (primarySsrc == -1)
        {
            return null;
        }

        SourceGroupPacketExtension simGroup =
            getGroup(SourceGroupPacketExtension.SEMANTICS_SIMULCAST, sourceGroups);

        int numTemporalLayers
            = ENABLE_SVC ? VP8_SIMULCAST_TEMPORAL_LAYERS : 1;

        int numSpatialLayers = simGroup == null ? 1 : simGroup.getSources().size();

        int numEncodings = numSpatialLayers * numTemporalLayers;

        RTPEncodingDesc[] rtpEncodings = new RTPEncodingDesc[numEncodings];

        MediaStreamTrackDesc track = new MediaStreamTrackDesc(
            mediaStreamTrackReceiver, rtpEncodings, simGroup != null);

        if (simGroup != null)
        {
            Long[] ssrcs = simGroup.getSources()
                .stream()
                .map(SourcePacketExtension::getSSRC)
                .toArray(Long[]::new);

            Map<Long, List<Pair<Long, String>>> allSecondarySsrcs = getAllSecondarySsrcs(ssrcs, sourceGroups);

            RTPEncodingDesc[] encodings =
                createRTPEncodings(track, ssrcs, numSpatialLayers, numTemporalLayers, allSecondarySsrcs);
            System.arraycopy(encodings, 0, rtpEncodings, 0, encodings.length);
        }
        else
        {
            Long[] ssrcs = new Long[] { primarySsrc };
            Map<Long, List<Pair<Long, String>>> allSecondarySsrcs =
                getAllSecondarySsrcs(ssrcs, sourceGroups);
            RTPEncodingDesc[] encodings =
                createRTPEncodings(track, ssrcs, numSpatialLayers, numTemporalLayers, allSecondarySsrcs);
            System.arraycopy(encodings, 0, rtpEncodings, 0, encodings.length);
        }

        return new MediaStreamTrackDesc[] { track };
    }

    /**
     * Calculates the subjective quality index of an RTP flow specified by its
     * stream index (simulcast), spatial index (SVC) and temporal index (SVC).
     *
     * @param streamIdx the stream index.
     * @param spatialIdx the spatial layer index.
     * @param temporalIdx the temporal layer index.
     *
     * @return the subjective quality index of the flow specified in the
     * arguments.
     */
    private static int qid(int streamIdx, int spatialIdx, int temporalIdx,
                           int spatialLen, int temporalLen)
    {
        return streamIdx * spatialLen * temporalLen
            + spatialIdx * temporalLen + temporalIdx;
    }


    static class Pair<K, V>
    {
        private K key;
        private V value;

        Pair(K k, V v)
        {
            this.key = k;
            this.value = v;
        }

        public K getKey()
        {
            return key;
        }

        public V getValue()
        {
            return value;
        }
    }
}
