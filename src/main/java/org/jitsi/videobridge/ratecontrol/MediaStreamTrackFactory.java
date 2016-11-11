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
package org.jitsi.videobridge.ratecontrol;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.service.neomedia.*;

import java.util.*;

/**
 * Creates {@link MediaStreamTrackImpl}s from signaling.
 *
 * @author George Politis
 */
public class MediaStreamTrackFactory
{
    /**
     * Creates the {@link MediaStreamTrackImpl}s from signaling params.
     *
     * @param sources  The <tt>List</tt> of <tt>SourcePacketExtension</tt> that
     * describes the list of sources of this <tt>RtpChannel</tt> and that is
     * used as the input in the update of the Sets the <tt>Set</tt> of the SSRCs
     * that this <tt>RtpChannel</tt> has signaled.
     * @param sourceGroups
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

        List<MediaStreamTrack> tracks = new ArrayList<>();
        List<Long> grouped = new ArrayList<>();

        int idxTrack = 0;
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

                if ("sim".equalsIgnoreCase(sg.getSemantics())
                    && groupSources.size() >= 2)
                {
                    simGroups.add(sg);
                }
                else if ("fid".equalsIgnoreCase(sg.getSemantics())
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
                    RTPEncodingImpl[] independentEncodings
                        = new RTPEncodingImpl[simSources.size()];
                    MediaStreamTrackImpl track = new MediaStreamTrackImpl(
                        idxTrack++, mediaStreamTrackReceiver, rtpEncodings,
                        independentEncodings);

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

                        double resolutionScale
                            = (1 << (simSources.size())) / (1 << (i + 1));
                        double frameRateScale = 4.0;
                        int idxEncoding = i * 3;
                        RTPEncodingImpl encoding = new RTPEncodingImpl(
                            track, idxEncoding, primarySSRC, rtxSSRC,
                            frameRateScale, resolutionScale, null);

                        independentEncodings[i] = encoding;
                        rtpEncodings[idxEncoding] = encoding;

                        idxEncoding = i * 3 + 1;
                        frameRateScale = 2.0;
                        encoding = new RTPEncodingImpl(
                            track, idxEncoding, primarySSRC, rtxSSRC,
                            frameRateScale, resolutionScale,
                            new RTPEncodingImpl[] { encoding });
                        rtpEncodings[idxEncoding] = encoding;

                        idxEncoding = i * 3 + 2;
                        frameRateScale = 1.0;
                        encoding = new RTPEncodingImpl(
                            track, idxEncoding, primarySSRC, rtxSSRC,
                            frameRateScale, resolutionScale,
                            new RTPEncodingImpl[] { encoding });
                        rtpEncodings[idxEncoding] = encoding;
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
                        idxTrack++, mediaStreamTrackReceiver, encodings);
                    encodings[0]
                        = new RTPEncodingImpl(track, 0, primarySSRC, rtxSSRC);

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
                int i = 0;
                long mediaSSRC = spe.getSSRC();

                if (!grouped.contains(mediaSSRC))
                {
                    RTPEncodingImpl[] encodings = new RTPEncodingImpl[1];
                    MediaStreamTrackImpl track = new MediaStreamTrackImpl(
                        idxTrack++, mediaStreamTrackReceiver, encodings);
                    encodings[0] = new RTPEncodingImpl(track, 0, mediaSSRC);
                    tracks.add(track);
                }
            }
        }

        return tracks.toArray(new MediaStreamTrackImpl[tracks.size()]);
    }
}
