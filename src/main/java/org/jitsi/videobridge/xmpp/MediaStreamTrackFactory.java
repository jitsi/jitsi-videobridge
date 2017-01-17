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

                    int encodingslen = includeTemporal
                        ? simSources.size() * 3 : simSources.size();

                    RTPEncodingDesc[] rtpEncodings
                        = new RTPEncodingDesc[encodingslen];
                    MediaStreamTrackDesc track = new MediaStreamTrackDesc(
                        mediaStreamTrackReceiver, rtpEncodings);

                    for (int i = 0; i < simSources.size(); i++)
                    {
                        SourcePacketExtension spe = simSources.get(i);
                        long primarySSRC = spe.getSSRC();
                        grouped.add(primarySSRC);

                        long rtxSSRC = -1;
                        if (rtxPairs.containsKey(primarySSRC))
                        {
                            rtxSSRC = rtxPairs.remove(primarySSRC);
                            grouped.add(rtxSSRC);
                        }

                        if (includeTemporal)
                        {
                            int subjectiveQualityIdx = i * 3;
                            RTPEncodingDesc encoding = new RTPEncodingDesc(
                                track, subjectiveQualityIdx, primarySSRC, rtxSSRC,
                                0, null);

                            rtpEncodings[subjectiveQualityIdx] = encoding;

                            subjectiveQualityIdx = i * 3 + 1;
                            encoding = new RTPEncodingDesc(
                                track, subjectiveQualityIdx, primarySSRC, rtxSSRC,
                                1, new RTPEncodingDesc[]{encoding});
                            rtpEncodings[subjectiveQualityIdx] = encoding;

                            subjectiveQualityIdx = i * 3 + 2;
                            encoding = new RTPEncodingDesc(
                                track, subjectiveQualityIdx, primarySSRC, rtxSSRC,
                                2, new RTPEncodingDesc[]{encoding});
                            rtpEncodings[subjectiveQualityIdx] = encoding;
                        }
                        else
                        {
                            RTPEncodingDesc encoding = new RTPEncodingDesc(
                                track, i, primarySSRC, rtxSSRC, -1, null);

                            rtpEncodings[i] = encoding;
                        }
                    }

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
