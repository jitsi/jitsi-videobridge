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

import java.util.*;

/**
 * A factory of {@link MediaStreamTrackDesc}s from jingle signaling.
 *
 * @author George Politis
 */
public class MediaStreamTrackFactory
{
    /**
     * The default number of temporal layers to use.
     */
    private static final int DEFAULT_NUM_TEMPORAL_LAYERS = 3;

    /**
     * Creates simulcast encodings.
     *
     * @param track the track that will own the temporal encodings.
     * @param primary the array of the primary SSRCs for the simulcast streams.
     * @param rtx the array of the RTX SSRCs for the simulcast streams.
     * @param tempoLen the number of temporal encodings per simulcast stream.
     * @return an array that holds the simulcast encodings.
     */
    public static RTPEncodingDesc[] createRTPEncodings(
        MediaStreamTrackDesc track, long[] primary, long[] rtx, int tempoLen)
    {
        RTPEncodingDesc[] rtpEncodings
            = new RTPEncodingDesc[primary.length * tempoLen];

        for (int i = 0; i < rtpEncodings.length; i++)
        {
            int streamIdx = i / tempoLen, tempoIdx = i % tempoLen;

            // The previous temporal layer is the dependency, if higher than
            // base.
            boolean hasDependency = tempoIdx % tempoLen != 0;

            RTPEncodingDesc[] dependency = hasDependency
                ? new RTPEncodingDesc[] { rtpEncodings[i - 1] } : null;

            int temporalId = tempoLen > 1 ? tempoIdx : -1;

            rtpEncodings[i] = new RTPEncodingDesc(
                track, i, primary[streamIdx], rtx[streamIdx],
                temporalId, dependency);
        }

        return rtpEncodings;
    }

    /**
     * Creates {@link MediaStreamTrackDesc}s from signaling params.
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
        List<SourceGroupPacketExtension> sourceGroups,
        boolean includeTemporal)
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

                    int numOfTempo
                        = includeTemporal ? DEFAULT_NUM_TEMPORAL_LAYERS : 1;
                    int encodingsLen = streamLen * numOfTempo;

                    RTPEncodingDesc[] rtpEncodings
                        = new RTPEncodingDesc[encodingsLen];

                    MediaStreamTrackDesc track = new MediaStreamTrackDesc(
                        mediaStreamTrackReceiver, rtpEncodings);

                    RTPEncodingDesc[] simulcastEncodings = createRTPEncodings(
                        track, primary, rtx, numOfTempo);

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

                    RTPEncodingDesc[] encodings = new RTPEncodingDesc[1];
                    MediaStreamTrackDesc track = new MediaStreamTrackDesc(
                        mediaStreamTrackReceiver, encodings);
                    encodings[0]
                        = new RTPEncodingDesc(track, primarySSRC, rtxSSRC);

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
                    RTPEncodingDesc[] encodings = new RTPEncodingDesc[1];
                    MediaStreamTrackDesc track = new MediaStreamTrackDesc(
                        mediaStreamTrackReceiver, encodings);
                    encodings[0] = new RTPEncodingDesc(track, mediaSSRC);
                    tracks.add(track);
                }
            }
        }

        return tracks.toArray(new MediaStreamTrackDesc[tracks.size()]);
    }
}
