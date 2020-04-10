/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.junit.*;
import org.junit.runner.*;
import org.powermock.modules.junit4.*;
import org.powermock.reflect.*;

import java.util.*;

import static org.junit.Assert.*;
import static org.powermock.api.easymock.PowerMock.*;

@RunWith(PowerMockRunner.class)
public class MediaStreamTrackFactoryTest
{
    private SourceGroupPacketExtension createGroup(
        String semantics, SourcePacketExtension... sources)
    {
        SourceGroupPacketExtension sgpe = new SourceGroupPacketExtension();
        sgpe.setSemantics(semantics);
        if (sources.length > 0)
        {
            sgpe.addSources(Arrays.asList(sources));
        }

        return sgpe;
    }

    private SourcePacketExtension createSource(Long ssrc)
    {
        SourcePacketExtension spe = new SourcePacketExtension();
        if (ssrc != null)
        {
            spe.setSSRC(ssrc);
        }

        return spe;
    }

    @After
    public void tearDown()
    {
        verifyAll();
    }

    // 1 video stream -> 1 track, 1 encoding
    @Test
    public void createMediaStreamTrack()
        throws Exception
    {
        replayAll();

        Whitebox.setInternalState(
                MediaStreamTrackFactory.class, "ENABLE_SVC", false);

        long videoSsrc = 12345;

        SourcePacketExtension videoSource = createSource(videoSsrc);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                Collections.singletonList(videoSource), Collections.emptyList());

        assertNotNull(tracks);
        assertEquals(1, tracks.length);
        MediaStreamTrackDesc track = tracks[0];
        assertEquals(1, track.getRTPEncodings().length);
    }

    // 1 video stream, 1 rtx -> 1 track, 1 encoding
    @Test
    public void createMediaStreamTracks1()
        throws
        Exception
    {
        replayAll();

        Whitebox.setInternalState(
                MediaStreamTrackFactory.class, "ENABLE_SVC", false);

        long videoSsrc = 12345;
        long rtxSsrc = 54321;

        SourcePacketExtension videoSource = createSource(videoSsrc);
        SourcePacketExtension rtx = createSource(rtxSsrc);

        SourceGroupPacketExtension rtxGroup
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource, rtx);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                Arrays.asList(videoSource, rtx), Arrays.asList(rtxGroup));

        assertNotNull(tracks);
        assertEquals(1, tracks.length);
        MediaStreamTrackDesc track = tracks[0];
        assertEquals(1, track.getRTPEncodings().length);
    }

    // 3 sim streams, 3 rtx -> 1 track, 3 encodings
    @Test
    public void createMediaStreamTracks2()
        throws
        Exception
    {
        replayAll();

        Whitebox.setInternalState(
                MediaStreamTrackFactory.class, "ENABLE_SVC", false);

        long videoSsrc1 = 12345;
        long videoSsrc2 = 23456;
        long videoSsrc3 = 34567;
        long rtxSsrc1 = 54321;
        long rtxSsrc2 = 43215;
        long rtxSsrc3 = 32154;

        SourcePacketExtension videoSource1 = createSource(videoSsrc1);
        SourcePacketExtension videoSource2 = createSource(videoSsrc2);
        SourcePacketExtension videoSource3 = createSource(videoSsrc3);
        SourcePacketExtension rtx1 = createSource(rtxSsrc1);
        SourcePacketExtension rtx2 = createSource(rtxSsrc2);
        SourcePacketExtension rtx3 = createSource(rtxSsrc3);

        SourceGroupPacketExtension simGroup
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                videoSource1,
                videoSource2,
                videoSource3);
        SourceGroupPacketExtension rtxGroup1
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource1, rtx1);
        SourceGroupPacketExtension rtxGroup2
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource2, rtx2);
        SourceGroupPacketExtension rtxGroup3
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource3, rtx3);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                Arrays.asList(
                    videoSource1, videoSource2, videoSource3, rtx1, rtx2, rtx3),
                Arrays.asList(simGroup, rtxGroup1, rtxGroup2, rtxGroup3));

        assertNotNull(tracks);
        assertEquals(1, tracks.length);
        MediaStreamTrackDesc track = tracks[0];
        assertEquals(3, track.getRTPEncodings().length);
    }

    // 3 sim streams, svc enabled, 3 rtx -> 1 track, 3 encodings
    @Test
    public void createMediaStreamTracks3()
        throws
        Exception
    {
        replayAll();

        // Here we add an override for the config service for a specific setting
        // NOTE: we can't do this via the mock return values, because the mock
        // values are only read once for the entire test class (because the
        // fields are static)
        Whitebox.setInternalState(
            MediaStreamTrackFactory.class, "ENABLE_SVC", true);

        long videoSsrc1 = 12345;
        long videoSsrc2 = 23456;
        long videoSsrc3 = 34567;
        long rtxSsrc1 = 54321;
        long rtxSsrc2 = 43215;
        long rtxSsrc3 = 32154;

        SourcePacketExtension videoSource1 = createSource(videoSsrc1);
        SourcePacketExtension videoSource2 = createSource(videoSsrc2);
        SourcePacketExtension videoSource3 = createSource(videoSsrc3);
        SourcePacketExtension rtx1 = createSource(rtxSsrc1);
        SourcePacketExtension rtx2 = createSource(rtxSsrc2);
        SourcePacketExtension rtx3 = createSource(rtxSsrc3);

        SourceGroupPacketExtension simGroup
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                videoSource1, videoSource2, videoSource3);
        SourceGroupPacketExtension rtxGroup1
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource1, rtx1);
        SourceGroupPacketExtension rtxGroup2
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource2, rtx2);
        SourceGroupPacketExtension rtxGroup3
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource3, rtx3);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                Arrays.asList(
                    videoSource1, videoSource2, videoSource3, rtx1, rtx2, rtx3),
                Arrays.asList(simGroup, rtxGroup1, rtxGroup2, rtxGroup3));

        assertNotNull(tracks);
        assertEquals(1, tracks.length);
        MediaStreamTrackDesc track = tracks[0];
        assertEquals(9, track.getRTPEncodings().length);
    }

    // 3 sim streams with rtx, 1 stream with rtx, 1 stream without rtx
    @Test
    public void createMediaStreamTracks4()
        throws Exception
    {
        replayAll();

        Whitebox.setInternalState(
            MediaStreamTrackFactory.class, "ENABLE_SVC", true);

        long videoSsrc1 = 12345;
        long videoSsrc2 = 23456;
        long videoSsrc3 = 34567;
        long videoSsrc4 = 45678;
        long videoSsrc5 = 56789;
        long rtxSsrc1 = 54321;
        long rtxSsrc2 = 43215;
        long rtxSsrc3 = 32154;
        long rtxSsrc4 = 21543;

        SourcePacketExtension videoSource1 = createSource(videoSsrc1);
        SourcePacketExtension videoSource2 = createSource(videoSsrc2);
        SourcePacketExtension videoSource3 = createSource(videoSsrc3);
        SourcePacketExtension videoSource4 = createSource(videoSsrc4);
        SourcePacketExtension videoSource5 = createSource(videoSsrc5);
        SourcePacketExtension rtx1 = createSource(rtxSsrc1);
        SourcePacketExtension rtx2 = createSource(rtxSsrc2);
        SourcePacketExtension rtx3 = createSource(rtxSsrc3);
        SourcePacketExtension rtx4 = createSource(rtxSsrc4);

        SourceGroupPacketExtension simGroup
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                videoSource1,
                videoSource2,
                videoSource3);
        SourceGroupPacketExtension rtxGroup1
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource1, rtx1);
        SourceGroupPacketExtension rtxGroup2
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource2, rtx2);
        SourceGroupPacketExtension rtxGroup3
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID, videoSource3, rtx3);
        SourceGroupPacketExtension rtxGroup4
            = createGroup(
            SourceGroupPacketExtension.SEMANTICS_FID, videoSource4, rtx4);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                Arrays.asList(
                    videoSource1, videoSource2, videoSource3, videoSource4,
                    videoSource5, rtx1, rtx2, rtx3, rtx4),
                Arrays.asList(
                    simGroup, rtxGroup1, rtxGroup2, rtxGroup3, rtxGroup4));

        assertNotNull(tracks);
        assertEquals(3, tracks.length);
        assertEquals(9, tracks[0].getRTPEncodings().length);
        assertEquals(1, tracks[1].getRTPEncodings().length);
        assertEquals(1, tracks[2].getRTPEncodings().length);
    }

    @Test
    public void testEmptySimGroup()
    {
        replayAll();

        long videoSsrc1 = 12345;

        SourcePacketExtension videoSource1 = createSource(videoSsrc1);

        SourceGroupPacketExtension simGroup
            = createGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                Arrays.asList(
                    videoSource1),
                Arrays.asList(simGroup));

        assertNotNull(tracks);
        assertEquals(1, tracks.length);
        MediaStreamTrackDesc track = tracks[0];
        assertEquals(1, track.getRTPEncodings().length);
    }

    @Test
    public void testEmptySource()
    {
        replayAll();

        SourcePacketExtension videoSource1 = createSource(null);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                Arrays.asList(videoSource1),
                Collections.emptyList()
            );

        assertNotNull(tracks);
        assertEquals(0, tracks.length);
    }
}
