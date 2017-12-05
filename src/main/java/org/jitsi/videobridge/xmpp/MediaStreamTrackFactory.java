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

    /**
     * Creates encodings.
     *
     * @param track the track that will own the temporal encodings.
     * @param primary the array of the primary SSRCs for the simulcast streams.
     * @param spatialLen the number of spatial encodings per simulcast stream.
     * @param temporalLen the number of temporal encodings per simulcast stream.
     * @param secondarySsrcs a map of primary ssrc -> list of pairs, where each
     * pair has the secondary ssrc as its key, and the type (rtx, etc.) as its
     * value
     * @return an array that holds the simulcast encodings.
     */
    private static RTPEncodingDesc[] createRTPEncodings(
        MediaStreamTrackDesc track, long[] primary,
        int spatialLen, int temporalLen, Map<Long, SecondarySsrcs> secondarySsrcs)
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
                    SecondarySsrcs ssrcSecondarySsrcs = secondarySsrcs.get(primary[streamIdx]);
                    if (ssrcSecondarySsrcs != null)
                    {
                        ssrcSecondarySsrcs.forEach(ssrcSecondarySsrc -> {
                            rtpEncodings[idx].addSecondarySsrc(ssrcSecondarySsrc.ssrc, ssrcSecondarySsrc.type);
                        });
                    }

                    frameRate *= 2;
                }
            }

            height *= 2;
        }
        return rtpEncodings;

    }

    /**
     * Get the 'secondary' ssrcs for the given primary ssrc.  'Secondary' here
     * is defined as things like rtx or fec ssrcs.
     * @param ssrc the primay ssrc for which to get the secondary ssrcs
     * @param sourceGroups the source groups
     * @return a map of secondary ssrc -> type (rtx, fec, etc.)
     */
    private static List<SecondarySsrc> getSecondarySsrcs(long ssrc, List<SourceGroupPacketExtension> sourceGroups)
    {
        List<SecondarySsrc> secondarySsrcs = new ArrayList<>();
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
                secondarySsrcs.add(new SecondarySsrc(groupSecondarySsrc, sourceGroup.getSemantics()));
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
    private static Map<Long, SecondarySsrcs> getAllSecondarySsrcs(long[] ssrcs, List<SourceGroupPacketExtension> sourceGroups)
    {
        Map<Long, SecondarySsrcs> allSecondarySsrcs = new HashMap<>();

        for (long ssrc : ssrcs)
        {
            List<SecondarySsrc> secondarySsrcs = getSecondarySsrcs(ssrc, sourceGroups);
            allSecondarySsrcs.put(ssrc, new SecondarySsrcs(secondarySsrcs));
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
        boolean hasSources = sources != null && !sources.isEmpty();
        boolean hasGroups = sourceGroups != null && !sourceGroups.isEmpty();

        if (!hasSources && !hasGroups)
        {
            return null;
        }

        List<MediaStreamTrackDesc> tracks = new ArrayList<>();
        List<Long> grouped = new ArrayList<>();

        if (hasGroups)
        {
            List<SourceGroupPacketExtension> simGroups = new ArrayList<>();
            Map<Long, Long> rtxPairs = new TreeMap<>();

            for (SourceGroupPacketExtension sg : sourceGroups)
            {
                List<SourcePacketExtension> groupSources = sg.getSources();
                if (groupSources == null || groupSources.isEmpty())
                {
                    continue;
                }

                if (SourceGroupPacketExtension
                    .SEMANTICS_SIMULCAST.equalsIgnoreCase(sg.getSemantics())
                    && groupSources.size() >= 2)
                {
                    simGroups.add(sg);
                }
                else if (SourceGroupPacketExtension.SEMANTICS_FID
                    .equalsIgnoreCase(sg.getSemantics())
                    && groupSources.size() == 2)
                {
                    rtxPairs.put(
                        groupSources.get(0).getSSRC(),
                        groupSources.get(1).getSSRC());
                }
            }

            if (!simGroups.isEmpty())
            {
                for (SourceGroupPacketExtension simGroup : simGroups)
                {
                    List<SourcePacketExtension> simSources
                        = simGroup.getSources();

                    int streamLen = simSources.size();

                    long[] primary = new long[streamLen],
                        rtx = new long[streamLen];

                    for (int i = 0; i < streamLen; i++)
                    {
                        long primarySSRC = simSources.get(i).getSSRC();
                        primary[i] = primarySSRC;
                        grouped.add(primarySSRC);

                        long rtxSSRC = -1;
                        if (rtxPairs.containsKey(primarySSRC))
                        {
                            rtxSSRC = rtxPairs.remove(primarySSRC);
                            grouped.add(rtxSSRC);
                        }
                        rtx[i] = rtxSSRC;
                    }

                    int numOfTemporal
                        = ENABLE_SVC ? VP8_SIMULCAST_TEMPORAL_LAYERS : 1;
                    int encodingsLen = streamLen * numOfTemporal;

                    RTPEncodingDesc[] rtpEncodings
                        = new RTPEncodingDesc[encodingsLen];

                    MediaStreamTrackDesc track = new MediaStreamTrackDesc(
                        mediaStreamTrackReceiver, rtpEncodings, true);

                    Map<Long, SecondarySsrcs> allSecondarySsrcs =
                        getAllSecondarySsrcs(primary, sourceGroups);

                    RTPEncodingDesc[] simulcastEncodings = createRTPEncodings(
                        track, primary, 1, numOfTemporal, allSecondarySsrcs);

                    System.arraycopy(simulcastEncodings, 0, rtpEncodings, 0,
                        simulcastEncodings.length);
                    tracks.add(track);
                }
            }

            if (!rtxPairs.isEmpty())
            {
                for (Map.Entry<Long, Long> fidEntry : rtxPairs.entrySet())
                {
                    Long primarySSRC = fidEntry.getKey();
                    Long rtxSSRC = fidEntry.getValue();

                    int numOfTemporal, numOfSpatial;
                    if (ENABLE_VP9_SVC && ENABLE_SVC)
                    {
                        numOfTemporal = VP9_SVC_TEMPORAL_LAYERS;
                        numOfSpatial = VP9_SVC_SPATIAL_LAYERS;
                    }
                    else
                    {
                        numOfSpatial = 1;
                        numOfTemporal = 1;
                    }

                    int encodingsLen = numOfSpatial * numOfTemporal;
                    RTPEncodingDesc[] encodings
                        = new RTPEncodingDesc[encodingsLen];
                    MediaStreamTrackDesc track = new MediaStreamTrackDesc(
                        mediaStreamTrackReceiver, encodings, false);

                    Map<Long, SecondarySsrcs> allSecondarySsrcs =
                        getAllSecondarySsrcs(new long[] { primarySSRC }, sourceGroups);

                    RTPEncodingDesc[] ret = createRTPEncodings(track,
                        new long[] { primarySSRC }, numOfSpatial, numOfTemporal,
                        allSecondarySsrcs);

                    System.arraycopy(ret, 0, encodings, 0, encodings.length);

                    grouped.add(primarySSRC);
                    grouped.add(rtxSSRC);
                    tracks.add(track);
                }
            }
        }

        if (hasSources)
        {
            for (SourcePacketExtension spe : sources)
            {
                long mediaSSRC = spe.getSSRC();

                if (!grouped.contains(mediaSSRC))
                {
                    int numOfTemporal, numOfSpatial;
                    if (ENABLE_VP9_SVC && ENABLE_SVC)
                    {
                        numOfTemporal = VP9_SVC_TEMPORAL_LAYERS;
                        numOfSpatial = VP9_SVC_SPATIAL_LAYERS;
                    }
                    else
                    {
                        numOfSpatial = 1;
                        numOfTemporal = 1;
                    }

                    int encodingsLen = numOfSpatial * numOfTemporal;
                    RTPEncodingDesc[] encodings
                        = new RTPEncodingDesc[encodingsLen];
                    MediaStreamTrackDesc track = new MediaStreamTrackDesc(
                        mediaStreamTrackReceiver, encodings, false);

                    Map<Long, SecondarySsrcs> allSecondarySsrcs =
                        getAllSecondarySsrcs(new long[] { mediaSSRC }, sourceGroups);

                    RTPEncodingDesc[] ret = createRTPEncodings(track,
                        new long[] { mediaSSRC }, numOfSpatial, numOfTemporal,
                        allSecondarySsrcs);

                    System.arraycopy(ret, 0, encodings, 0, encodings.length);
                    tracks.add(track);
                }
            }
        }

        return tracks.toArray(new MediaStreamTrackDesc[tracks.size()]);
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

    private static class SecondarySsrc
    {
        public long ssrc;
        public String type;

        public SecondarySsrc(long ssrc, String type)
        {
            this.ssrc = ssrc;
            this.type = type;
        }
    }

    private static class SecondarySsrcs
        implements Iterable<SecondarySsrc>
    {
        public List<SecondarySsrc> secondarySsrcs;

        public SecondarySsrcs(List<SecondarySsrc> secondarySsrcs)
        {
            this.secondarySsrcs = secondarySsrcs;
        }

        @Override
        public Iterator<SecondarySsrc> iterator()
        {
            return secondarySsrcs.iterator();
        }
    }
}
