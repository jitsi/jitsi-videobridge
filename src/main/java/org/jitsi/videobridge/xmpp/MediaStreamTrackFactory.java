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
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * A factory of {@link MediaStreamTrackDesc}s from jingle signaling.
 *
 * @author George Politis
 */
public class MediaStreamTrackFactory
{
    /**
     * The system property name that for a boolean that's controlling whether
     * or not to enable temporal scalability filtering for VP8.
     */
    public static final String ENABLE_VP8_SVC_PNAME = "org.jitsi" +
       ".videobridge.ENABLE_SVC" ;

    /**
     * The default number of temporal layers to use for VP8 simulcast.
     */
    private static final int VP8_SIMULCAST_TEMPORAL_LAYERS = 3;

    /**
     * The resolution of the base stream when activating simulcast for VP8.
     */
    private static final int VP8_SIMULCAST_BASE_LAYER_HEIGHT = 180;

    /**
     * The {@link Logger} used by the {@link MediaStreamTrackDesc} class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(MediaStreamTrackFactory.class);

    /**
     * libjitsi isn't aware of the group semantics names defined in
     * {@link SourceGroupPacketExtension}, which is how we distinguish secondary
     * ssrcs, so we'll translate them into constants defined in libjitsi
     */
    private static final Map<String, String> semanticsMap
        = new ConcurrentHashMap<>();

    static {
        semanticsMap.put(
                SourceGroupPacketExtension.SEMANTICS_FID, Constants.RTX);
        semanticsMap.put(
                SourceGroupPacketExtension.SEMANTICS_FEC,
                SourceGroupPacketExtension.SEMANTICS_FEC);
    }

    /**
     * The {@link MediaStreamTrackReceiver} that will receive the created
     * tracks.
     */
    private final MediaStreamTrackReceiver trackReceiver;

    /**
     * The Jingle sources to create tracks from.
     */
    private final List<SourcePacketExtension> sources;

    /**
     * The Jingle source groups to create tracks from.
     */
    private final List<SourceGroupPacketExtension> sourceGroups;

    /**
     * Helper object that maps primary ssrcs to  tracks. A track can
     * have multiple primary SSRCs, because of simulcast.
     */
    private Map<Long, MediaStreamTrackDesc> tracksMap = new TreeMap<>();

    /**
     * Ctor.
     *
     * @param mediaStreamTrackReceiver the {@link MediaStreamTrackReceiver} that
     * will receive the created {@link MediaStreamTrackDesc}s.
     * @param sources The {@link List} of {@link SourcePacketExtension} that
     * describes the list of jingle sources.
     * @param sourceGroups The {@link List} of
     * {@link SourceGroupPacketExtension} that describes the list of jingle
     * source groups.
     */
    public MediaStreamTrackFactory(
            MediaStreamTrackReceiver trackReceiver,
            List<SourcePacketExtension> sources,
            List<SourceGroupPacketExtension> sourceGroups)
    {
        this.trackReceiver = trackReceiver;
        this.sources = sources;
        this.sourceGroups = sourceGroups;
    }

    /**
     * Creates {@link MediaStreamTrackDesc}s from signaling params
     *
     * @return the {@link MediaStreamTrackReceiver} that are described in the
     * jingle sources and source groups.
     */
    public MediaStreamTrackDesc[] createMediaStreamTracks()
    {
        tracksMap.clear();

	if (sourceGroups != null && !sourceGroups.isEmpty())
	{
            // Make a copy of the groups because we delete any SIM groups we
            // find.
            List<SourceGroupPacketExtension> groupsCopy
                = new ArrayList<>(sourceGroups);

            // We first process the special SIM group. The SIM group represents
            // a single track (or video source) it has 3 spatial encodings and
            // each spatial encoding has 3 temporal encodings. The temporal
            // sub-encodings share the SSRCs of the parent spatial encoding. 
	    Iterator<SourceGroupPacketExtension> it = groupsCopy.iterator();

	    while (it.hasNext())
            {
		SourceGroupPacketExtension sourceGroup = it.next();
		if (sourceGroup.getSemantics().equalsIgnoreCase(
			    SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
		{
                    addVP8SimulcastTrack(sourceGroup);
                    
                    // We don't want to process this group EVER again!
                    it.remove(); 
                }
	    }

            // Now we're only left with RTX and FEC groups.
            for (SourceGroupPacketExtension sourceGroup : groupsCopy)
            {
                List<SourcePacketExtension> groupSources
                    = sourceGroup.getSources();

                if (groupSources.size() != 2)
                {
                    logger.warn(
                            "A valid RTX or FEC group has exactly 2 ssrcs.");
                    continue;
                }

                long primarySSRC = groupSources.get(0).getSSRC();
    
                MediaStreamTrackDesc track = addPlainTrack(primarySSRC);

                // Set the secondary SSRC for the matching encodings.
                long secondarySSRC = groupSources.get(1).getSSRC();
                tracksMap.put(secondarySSRC, track);
                for (RTPEncodingDesc encoding : track.getRTPEncodings())
                {
                    if (encoding.getPrimarySSRC() == primarySSRC)
                    {
                        encoding.addSecondarySsrc(
                                secondarySSRC,
                                semanticsMap.get(sourceGroup.getSemantics()));
                    }
                }
            }
	}
	
        // Process the free ssrcs
        if (sources != null && !sources.isEmpty())
        {
            for (SourcePacketExtension source : sources)
            {
                long primarySSRC = source.getSSRC();
                addPlainTrack(primarySSRC);
            }
        }

        Collection<MediaStreamTrackDesc> tracks = tracksMap.values();
	return tracks.toArray(new MediaStreamTrackDesc[tracks.size()]);
    }

    private MediaStreamTrackDesc addPlainTrack(long primarySSRC)
    {
        MediaStreamTrackDesc track = tracksMap.get(primarySSRC);

        // Check if we already have a track for this SSRC.
        if (track == null)
        {
            // It looks like this is the first time we see this SSRC,
            // so we create a new track for it.
            track = new MediaStreamTrackDesc(trackReceiver, false);
            track.setRTPEncodings(
                    new RTPEncodingDesc(track, primarySSRC));
            tracksMap.put(primarySSRC, track);
        }

        return track;
    }

    private void addVP8SimulcastTrack(SourceGroupPacketExtension simGroup)
    {
        MediaStreamTrackDesc track
            = new MediaStreamTrackDesc(trackReceiver, true);

        List<RTPEncodingDesc> encodings = new ArrayList<>();

        int qot = 0;
        int height = VP8_SIMULCAST_BASE_LAYER_HEIGHT;
        for (SourcePacketExtension source : simGroup.getSources())
        {
            double frameRate = 7.5;
            long primarySSRC = source.getSSRC();
            tracksMap.put(primarySSRC, track);

            RTPEncodingDesc previousTemporalLayer = null;
            for (int tid = 0;
                    tid < VP8_SIMULCAST_TEMPORAL_LAYERS; tid++)
            {
                RTPEncodingDesc[] dependencies
                    = previousTemporalLayer == null ? null
                    : new RTPEncodingDesc[] { previousTemporalLayer };
                previousTemporalLayer = new RTPEncodingDesc(
                        track, 
                        primarySSRC,
                        qot,
                        tid /* tid */,
                        -1 /* sid */, 
                        height /* height */,
                        frameRate /* frame rate */,
                        dependencies);

                encodings.add(previousTemporalLayer);

                qot += 1;
                frameRate *= 2;
            }

            height *= 2;
        }

        track.setRTPEncodings(encodings.toArray(
                    new RTPEncodingDesc[encodings.size()]));
    }
}
