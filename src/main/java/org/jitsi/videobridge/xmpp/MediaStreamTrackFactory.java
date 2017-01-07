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
 * A factory of {@link MediaStreamTrackImpl}s from jingle signaling.
 *
 * @author George Politis
 */
public class MediaStreamTrackFactory
{
    /**
     * Creates {@link MediaStreamTrackImpl}s from signaling params.
     *
     * @param mediaStreamTrackReceiver the {@link MediaStreamTrackReceiver} that
     * will receive the created {@link MediaStreamTrackImpl}s.
     * @param sources The {@link List} of {@link SourcePacketExtension} that
     * describes the list of jingle sources.
     * @param sourceGroups The {@link List} of
     * {@link SourceGroupPacketExtension} that describes the list of jingle
     * source groups.
     * @return the {@link MediaStreamTrackReceiver} that are described in the
     * jingle sources and source groups.
     */
    public static MediaStreamTrackImpl[] createMediaStreamTracks(
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

        List<MediaStreamTrackImpl> tracks = new ArrayList<>();
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

                    RTPEncodingImpl[] rtpEncodings
                        = new RTPEncodingImpl[simSources.size() * 3];
                    MediaStreamTrackImpl track = new MediaStreamTrackImpl(
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

                        int subjectiveQualityIdx = i * 3;
                        RTPEncodingImpl encoding = new RTPEncodingImpl(
                            track, subjectiveQualityIdx, primarySSRC, rtxSSRC,
                            0, null);

                        rtpEncodings[subjectiveQualityIdx] = encoding;

                        subjectiveQualityIdx = i * 3 + 1;
                        encoding = new RTPEncodingImpl(
                            track, subjectiveQualityIdx, primarySSRC, rtxSSRC,
                            1, new RTPEncodingImpl[] { encoding });
                        rtpEncodings[subjectiveQualityIdx] = encoding;

                        subjectiveQualityIdx = i * 3 + 2;
                        encoding = new RTPEncodingImpl(
                            track, subjectiveQualityIdx, primarySSRC, rtxSSRC,
                            2, new RTPEncodingImpl[] { encoding });
                        rtpEncodings[subjectiveQualityIdx] = encoding;
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

                    RTPEncodingImpl[] encodings = new RTPEncodingImpl[1];
                    MediaStreamTrackImpl track = new MediaStreamTrackImpl(
                        mediaStreamTrackReceiver, encodings);
                    encodings[0]
                        = new RTPEncodingImpl(track, primarySSRC, rtxSSRC);

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
                    RTPEncodingImpl[] encodings = new RTPEncodingImpl[1];
                    MediaStreamTrackImpl track = new MediaStreamTrackImpl(
                        mediaStreamTrackReceiver, encodings);
                    encodings[0] = new RTPEncodingImpl(track, mediaSSRC);
                    tracks.add(track);
                }
            }
        }

        return tracks.toArray(new MediaStreamTrackImpl[tracks.size()]);
    }
}
