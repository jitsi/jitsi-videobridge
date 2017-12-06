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
import org.jitsi.service.neomedia.*;

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
        = cfg.getBoolean(ENABLE_VP9_SVC_PNAME, false);

    /**
     * A boolean that's controlling whether or not to enable SVC filtering for
     * scalable video codecs.
     */
    private static final Boolean ENABLE_SVC
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
        MediaStreamTrackDesc track, TrackSsrcs primary,
        int spatialLen, int temporalLen, Map<Long, SecondarySsrcs> secondarySsrcs)
    {
        RTPEncodingDesc[] rtpEncodings
            = new RTPEncodingDesc[primary.size() * spatialLen * temporalLen];

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
        for (int streamIdx = 0; streamIdx < primary.size(); streamIdx++)
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
                        primary.get(streamIdx),
                        temporalId, spatialId, height, frameRate, dependencies);
                    SecondarySsrcs ssrcSecondarySsrcs = secondarySsrcs.get(primary.get(streamIdx));
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
    private static Map<Long, SecondarySsrcs> getAllSecondarySsrcs(TrackSsrcs ssrcs, List<SourceGroupPacketExtension> sourceGroups)
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
     * Get all groups which have the given semantics
     * @param semantics
     * @param groups
     * @return
     */
    private static List<SourceGroupPacketExtension> getGroups(final String semantics, final List<SourceGroupPacketExtension> groups)
    {
        return groups.stream()
            .filter(sg -> sg.getSemantics().equalsIgnoreCase(semantics))
            .collect(Collectors.toList());
    }

    /**
     * Removes all sources which correspond to an ssrc in trackSsrcs, or any
     * groups with a primary ssrc in trackSsrcs.  NOTE: modifies
     * sources and sourceGroups in place
     * @param trackSsrcs the primary ssrcs for which all corresponding ssrcs
     * will be removed from the given sources and groups
     * @param sources the set of sources
     * @param sourceGroups the set of groups
     */
    private static void removeReferences(TrackSsrcs trackSsrcs, List<SourcePacketExtension> sources,
                                         List<SourceGroupPacketExtension> sourceGroups)
    {
        sources.removeIf(source -> trackSsrcs.contains(source.getSSRC()));
        sourceGroups.removeIf(group -> {
            for (SourcePacketExtension source : group.getSources())
            {
                if (trackSsrcs.contains(source.getSSRC()))
                {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Given the sources and groups, return a list of the ssrcs for each
     * unique track
     * @param sources the set of sources
     * @param sourceGroups the set of source groups
     * @return a list of {@link TrackSsrcs}. Each TrackSsrc object represents
     * a set of primary video ssrcs belonging to a single track (video source)
     */
    private static List<TrackSsrcs> getTrackSsrcs(List<SourcePacketExtension> sources,
                                                  List<SourceGroupPacketExtension> sourceGroups)
    {
        //FIXME: determining the individual tracks should be done via msid,
        // but somewhere along the line we seem to lose the msid information
        // in the packet extensions
        List<TrackSsrcs> trackSsrcsList = new ArrayList<>();
        // We'll need to keep track of which sources and groups have been processed,
        // so make copies of the lists we've been given so we can modify them.
        List<SourcePacketExtension> sourcesCopy = new ArrayList<>(sources);
        List<SourceGroupPacketExtension> sourceGroupsCopy = new ArrayList<>(sourceGroups);

        // Note that the order we process these groups is important
        Arrays.asList(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            SourceGroupPacketExtension.SEMANTICS_FID,
            SourceGroupPacketExtension.SEMANTICS_FEC
        ).forEach(groupSem -> {
            List<SourceGroupPacketExtension> groups = getGroups(groupSem, sourceGroupsCopy);
            groups.forEach(group -> {
                List<Long> ssrcs;
                // For a simulcast group, all the ssrcs are considered primary ssrcs,
                // but for others, only the main ssrc of the group is
                if (groupSem.equalsIgnoreCase(SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
                {
                    ssrcs = group.getSources().stream()
                        .map(SourcePacketExtension::getSSRC)
                        .collect(Collectors.toList());
                }
                else
                {
                    ssrcs = Arrays.asList(group.getSources().get(0).getSSRC());
                }

                TrackSsrcs trackSsrcs = new TrackSsrcs(ssrcs);
                // Now we need to remove any groups with these ssrcs as their primary,
                // or sources that correspond to one of these ssrcs
                removeReferences(trackSsrcs, sourcesCopy, sourceGroupsCopy);

                trackSsrcsList.add(trackSsrcs);
            });
        });

        return trackSsrcsList;
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
        List<TrackSsrcs> trackSsrcsList = getTrackSsrcs(sources, sourceGroups);
        List<MediaStreamTrackDesc> tracks = new ArrayList<>();
        trackSsrcsList.forEach(trackSsrcs -> {
            // As of now, we only ever have 1 spatial layer per stream
            int numSpatialLayersPerStream = 1;
            int numTemporalLayersPerStream = 1;
            if (trackSsrcs.size() > 1 && ENABLE_SVC)
            {
                numTemporalLayersPerStream = VP8_SIMULCAST_TEMPORAL_LAYERS;
            }
            Map<Long, SecondarySsrcs> secondarySsrcs = getAllSecondarySsrcs(trackSsrcs,
                sourceGroups);
            MediaStreamTrackDesc track = createTrack(mediaStreamTrackReceiver,
                trackSsrcs, numSpatialLayersPerStream,
                numTemporalLayersPerStream, secondarySsrcs);
            tracks.add(track);
        });

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

    /**
     * Describes a single secondary ssrc and its group semantics to
     * its primary ssrc (e.g. FID or FEC-FR)
     */
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

    /**
     * Groups a set of secondary ssrcs for a single primary ssrc (e.g. its
     * rtx and fec ssrcs)
     */
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

    /**
     * Describes one or more primary video ssrcs for a single
     * {@link MediaStreamTrackDesc}
     */
    private static class TrackSsrcs
        implements Iterable<Long>
    {
        private List<Long> trackSsrcs;

        public TrackSsrcs(List<Long> trackSsrcs)
        {
            this.trackSsrcs = trackSsrcs;
        }

        public boolean contains(Long ssrc)
        {
            return trackSsrcs.contains(ssrc);
        }

        public int size()
        {
            return trackSsrcs.size();
        }

        public Long get(int index)
        {
            return trackSsrcs.get(index);
        }

        @Override
        public Iterator<Long> iterator()
        {
            return trackSsrcs.iterator();
        }
    }

    /**
     * Creates a single MediaStreamTrack with the given information
     * @param receiver the {@link MediaStreamTrackReceiver}
     * @param primarySsrcs the set of primary video ssrcs belonging to this track
     * @param numSpatialLayersPerStream the number of spatial layers per stream
     * for this track
     * @param numTemporalLayersPerStream the number of temporal layers per stream
     * for this track
     * @param allSecondarySsrcs a map of primary ssrc -> SecondarySsrcs, which lists
     * the ssrc and type of all the secondary ssrcs for a given primary (e.g.
     * its corresponding rtx and fec ssrcs)
     * @return the created MediaStreamTrack
     */
    private static MediaStreamTrackDesc createTrack(MediaStreamTrackReceiver receiver,
                                             TrackSsrcs primarySsrcs,
                                             int numSpatialLayersPerStream,
                                             int numTemporalLayersPerStream,
                                             Map<Long, SecondarySsrcs> allSecondarySsrcs)
    {
        int numEncodings = primarySsrcs.size() * numSpatialLayersPerStream * numTemporalLayersPerStream;
        boolean isSimulcast = primarySsrcs.size() > 1;
        RTPEncodingDesc[] rtpEncodings = new RTPEncodingDesc[numEncodings];
        MediaStreamTrackDesc track =
            new MediaStreamTrackDesc(receiver, rtpEncodings, isSimulcast);

        RTPEncodingDesc[] encodings = createRTPEncodings(track, primarySsrcs,
            numSpatialLayersPerStream, numTemporalLayersPerStream, allSecondarySsrcs);
        System.out.println("BRIAN: created encodings:\n ");
        for (int i = 0; i < encodings.length; ++i)
        {
            System.out.println(encodings[i]);
        }
        assert(encodings.length <= numEncodings);
        System.arraycopy(encodings, 0, rtpEncodings, 0, encodings.length);

        return track;
    }

}
