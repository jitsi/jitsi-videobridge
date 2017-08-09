package org.jitsi.videobridge.cc;

import org.easymock.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.junit.*;
import org.junit.runner.*;
import org.powermock.api.easymock.*;
import org.powermock.core.classloader.annotations.*;
import org.powermock.modules.junit4.*;

import static org.easymock.EasyMock.*;
import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.*;
import static org.powermock.api.easymock.PowerMock.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest({LibJitsi.class, MediaStreamTrackDesc.class})
public class SimulcastControllerTest
    extends EasyMockSupport
{
    BitrateController bitrateController;
    MediaStreamTrackDesc mediaStreamTrackDesc;
    BitstreamController bitstreamController;

    SimulcastController simulcastController;

    private void createAdaptiveStreamEncodingsHelper(
        int baseLayerIndex, RTPEncodingDesc[] encodings, int numAdaptiveLayers,
        long ssrc)
    {
        for (int i = baseLayerIndex; i < baseLayerIndex + numAdaptiveLayers; ++i)
        {
            RTPEncodingDesc desc = createMock(RTPEncodingDesc.class);
            if (i == baseLayerIndex)
            {
                expect(desc.getBaseLayer()).andReturn(desc).anyTimes();
            }
            else
            {
                RTPEncodingDesc baseLayerEncoding = encodings[baseLayerIndex];
                expect(desc.getBaseLayer()).andReturn(baseLayerEncoding).anyTimes();
            }
            expect(desc.getIndex()).andReturn(i).anyTimes();

            desc.incrReceivers();
            expectLastCall().anyTimes();

            desc.decrReceivers();
            expectLastCall().anyTimes();

            expect(desc.getPrimarySSRC()).andReturn(ssrc).anyTimes();

            expect(desc.requires(baseLayerIndex)).andReturn(true).anyTimes();
            if (i != baseLayerIndex)
            {
                expect(desc.requires(i)).andReturn(true).anyTimes(); // encoding requires itself
            }
            expect(desc.requires(anyInt())).andReturn(false).anyTimes();

            encodings[i] = desc;
        }
    }

    private RTPEncodingDesc[] createEncodings(boolean isAdaptive)
    {
        int numBaseLayers = 3;
        int numAdaptiveLayers = isAdaptive ? 3 : 1;
        int numEncodings = numBaseLayers * numAdaptiveLayers;

        RTPEncodingDesc[] rtpEncodings = new RTPEncodingDesc[numEncodings];
        for (int currBaseLayer = 0; currBaseLayer < numBaseLayers; ++currBaseLayer)
        {
            createAdaptiveStreamEncodingsHelper(
                currBaseLayer * numAdaptiveLayers, rtpEncodings, numAdaptiveLayers, (long)currBaseLayer);
        }

        return rtpEncodings;
    }

    @Before
    public void setUp()
    {
        mockStatic(LibJitsi.class);
        expect(LibJitsi.getConfigurationService()).andReturn(null).anyTimes();
        PowerMock.replay(LibJitsi.class);

        bitrateController = createMock(BitrateController.class);
        // This class requires the LibJitsi.getConfigurationService static method,
        // and even instantiating a mock seems to trigger it.  mockStatic for LibJitsi
        // (which would normally work) is causing problems because it requires
        // replaying here (since the next mock needs it) and i *think* replaying
        // that object is causing other problems...it doesn't make sense but
        // the error i'm getting is related to mediaStreamTrackDesc missing
        // a behavior definition when i'm still in mock mode
        mediaStreamTrackDesc = createMock(MediaStreamTrackDesc.class);
        bitstreamController = createMock(BitstreamController.class);

        simulcastController = new SimulcastController(bitrateController,
            mediaStreamTrackDesc, bitstreamController);
    }

    @Test
    public void accept()
        throws
        Exception
    {
        // State: currentIndex = 8, targetIndex = 2, stream is adaptive
        // Given: a keyframe from index 0
        // Expected: should be accepted, since index 2 relies on index 0

        expect(bitstreamController.getTargetIndex()).andReturn(2);
        expect(bitstreamController.getCurrentIndex()).andReturn(8);

        long pktSsrc = 12345;
        long pktTimestamp = 1000;
        RawPacket pkt = createMock(RawPacket.class);
        expect(pkt.getSSRCAsLong()).andReturn(pktSsrc);
        expect(pkt.getTimestamp()).andReturn(pktTimestamp);

        RTPEncodingDesc[] encodings = createEncodings(true);
        expect(mediaStreamTrackDesc.getRTPEncodings()).andReturn(encodings).anyTimes();

        FrameDesc frame = createMock(FrameDesc.class);
        expect(mediaStreamTrackDesc.findFrameDesc(eq(pktSsrc), eq(pktTimestamp))).andReturn(frame);

        replayAll();

        assertTrue(simulcastController.accept(pkt));

        verifyAll();
    }

}