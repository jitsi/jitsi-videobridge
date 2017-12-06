package org.jitsi.videobridge.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.easymock.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.junit.*;
import org.junit.runner.*;
import org.mockito.*;
import org.powermock.api.easymock.*;
import org.powermock.core.classloader.annotations.*;
import org.powermock.modules.junit4.*;

import javax.xml.transform.*;

import java.util.*;

import static java.util.Arrays.asList;
import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.powermock.api.easymock.PowerMock.replayAll;

@RunWith(PowerMockRunner.class)
@PrepareForTest(LibJitsi.class)
public class MediaStreamTrackFactoryTest
{
    private ConfigurationService mockConfigurationService;

    private SourceGroupPacketExtension createGroup(String semantics, SourcePacketExtension... sources)
    {
        SourceGroupPacketExtension sgpe = new SourceGroupPacketExtension();
        sgpe.setSemantics(semantics);
        sgpe.addSources(Arrays.asList(sources));

        return sgpe;
    }

    private SourcePacketExtension createSource(long ssrc)
    {
        SourcePacketExtension spe = new SourcePacketExtension();
        spe.setSSRC(ssrc);

        return spe;
    }

    private void setUpMockConfigurationService()
    {
        PowerMock.mockStatic(LibJitsi.class);
        mockConfigurationService = PowerMock.createMock(ConfigurationService.class);
        expect(LibJitsi.getConfigurationService()).andReturn(mockConfigurationService).anyTimes();

        //Capture<Boolean> boolCapture = Capture.newInstance();
        //expect(cs.getBoolean(EasyMock.anyString(), EasyMock.captureBoolean(boolCapture))).andReturn(boolCapture.getValue());
        expect(mockConfigurationService.getBoolean(EasyMock.anyString(), EasyMock.anyBoolean())).andAnswer(() -> (boolean)EasyMock.getCurrentArguments()[1]).anyTimes();
        expect(mockConfigurationService.getInt(EasyMock.anyString(), EasyMock.anyInt())).andAnswer(() -> (int)EasyMock.getCurrentArguments()[1]).anyTimes();
    }



    @Before
    public void setUp()
    {
        setUpMockConfigurationService();
    }

    // 1 video stream, 1 rtx -> 1 track, 1 encoding
    @Test
    public void createMediaStreamTracks1()
        throws
        Exception
    {
        replayAll();

        long videoSsrc = 12345;
        long rtxSsrc = 54321;

        SourcePacketExtension videoSource = createSource(videoSsrc);
        SourcePacketExtension rtx = createSource(rtxSsrc);

        SourceGroupPacketExtension rtxGroup = createGroup(SourceGroupPacketExtension.SEMANTICS_FID, videoSource, rtx);

        MediaStreamTrackReceiver receiver = new MediaStreamTrackReceiver(null);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(receiver,
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

        SourceGroupPacketExtension simGroup = createGroup(SourceGroupPacketExtension.SEMANTICS_SIMULCAST, videoSource1, videoSource2, videoSource3);
        SourceGroupPacketExtension rtxGroup1 = createGroup(SourceGroupPacketExtension.SEMANTICS_FID, videoSource1, rtx1);
        SourceGroupPacketExtension rtxGroup2 = createGroup(SourceGroupPacketExtension.SEMANTICS_FID, videoSource2, rtx2);
        SourceGroupPacketExtension rtxGroup3 = createGroup(SourceGroupPacketExtension.SEMANTICS_FID, videoSource3, rtx3);

        MediaStreamTrackReceiver receiver = new MediaStreamTrackReceiver(null);

        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(receiver,
                Arrays.asList(videoSource1, videoSource2, videoSource3, rtx1, rtx2, rtx3), Arrays.asList(simGroup, rtxGroup1, rtxGroup2, rtxGroup3));

        assertNotNull(tracks);
        assertEquals(1, tracks.length);
        MediaStreamTrackDesc track = tracks[0];
        assertEquals(3, track.getRTPEncodings().length);
    }
}