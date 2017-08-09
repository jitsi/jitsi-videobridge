/*
 * Copyright @ 2017 Atlassian Pty Ltd
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
package org.jitsi.videobridge.cc;

import org.easymock.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.function.*;
import org.junit.*;
import org.junit.runner.*;
import org.powermock.core.classloader.annotations.*;
import org.powermock.modules.junit4.*;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;
import static org.powermock.api.easymock.PowerMock.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest(LibJitsi.class)
public class BitstreamControllerTest
    extends EasyMockSupport
{
    private BitstreamController bitstreamController;
    // Mock SeenFrameAllocator
    private BitstreamController.SeenFrameAllocator seenFrameAllocator;

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

    private FrameDesc createFrame(int qualityIndex, RTPEncodingDesc[] encodings, long timestamp)
    {
        FrameDesc sourceFrame = createMock(FrameDesc.class);
        expect(sourceFrame.firstSequenceNumberKnown()).andReturn(true).anyTimes();
        expect(sourceFrame.getTimestamp()).andReturn(timestamp).anyTimes();
        expect(sourceFrame.isIndependent()).andReturn(true).anyTimes();
        expect(sourceFrame.getTrackRTPEncodings()).andReturn(encodings).anyTimes();
        expect(sourceFrame.getRTPEncoding()).andReturn(encodings[qualityIndex]).anyTimes();

        return sourceFrame;
    }

    private RawPacket createPacket()
    {
        int arbitraryPacketLength = 500;
        RawPacket pkt = createMock(RawPacket.class);
        expect(pkt.getLength()).andReturn(arbitraryPacketLength).anyTimes();

        return pkt;
    }

    @Before
    public void setUp()
    {
        mockStatic(LibJitsi.class);
        expect(LibJitsi.getConfigurationService()).andReturn(null);
        seenFrameAllocator =
            createMock(BitstreamController.SeenFrameAllocator.class);
        bitstreamController = new BitstreamController(seenFrameAllocator);
    }

    @Test
    public void accept()
        throws
        Exception
    {
        // Adaptive stream, unknown start seq num for frame

        // The set of encodings for this stream
        RTPEncodingDesc[] encodings = createEncodings(true);

        FrameDesc sourceFrame = createMock(FrameDesc.class);
        expect(sourceFrame.firstSequenceNumberKnown()).andReturn(false);
        expect(sourceFrame.getRTPEncoding()).andReturn(encodings[0]);
        RawPacket pkt = createPacket();
        MediaStream sourceStream = createMock(MediaStream.class);

        replayAll();

        bitstreamController.setTL0Idx(0, encodings);
        assertFalse(bitstreamController.accept(sourceFrame, pkt, sourceStream));

        verifyAll();
    }

    @Test
    public void accept1()
        throws
        Exception
    {
        // State: Non-adaptive stream, haven't sent frame, not suspended
        // Given: new keyframe from target index
        // Expected: should be accepted

        // The set of encodings for this stream
        RTPEncodingDesc[] encodings = createEncodings(false);

        // The frame we'll pass in to accept
        long frameTs = 1000;
        int frameIndex = 0;
        FrameDesc sourceFrame = createFrame(frameIndex, encodings, frameTs);
        RawPacket pkt = createPacket();

        // The source stream
        MediaStream sourceStream = createMock(MediaStream.class);

        // The SeenFrame we'll inject into the BitstreamController via the
        // SeenFrameAllocator
        SeenFrame seenFrame = createMock(SeenFrame.class);
        expect(seenFrameAllocator.getOrCreate()).andReturn(seenFrame);
        seenFrame.reset(frameTs, null, null, -1, -1, true, false);
        expectLastCall().anyTimes();
        expect(seenFrame.accept(sourceFrame, pkt, true)).andReturn(true);

        replayAll();

        bitstreamController.setTL0Idx(0, encodings);
        assertTrue(bitstreamController.accept(sourceFrame, pkt, sourceStream));

        verifyAll();
    }

    @Test
    public void accept2()
        throws
        Exception
    {
        // State: Adaptive stream, haven't sent frame, not suspended,
        //  targetIndex = 8
        // Given: keyframe from index 6
        // Expected: should be accepted (the target index layer relies on
        //  layer 6)

        // The set of encodings for this stream
        RTPEncodingDesc[] encodings = createEncodings(true);

        // The frame we'll pass in to accept
        long frameTs = 1000;
        int frameIndex = 6;
        FrameDesc sourceFrame = createFrame(frameIndex, encodings, frameTs);

        RawPacket pkt = createPacket();

        // The source stream and a set of encodings for this stream
        MediaStream sourceStream = createMock(MediaStream.class);

        // The SeenFrame we'll inject into the BitstreamController via the
        // SeenFrameAllocator
        SeenFrame seenFrame = createMock(SeenFrame.class);
        expect(seenFrameAllocator.getOrCreate()).andReturn(seenFrame);
        seenFrame.reset(frameTs, null, null, -1, -1, true, true);
        expectLastCall();
        expect(seenFrame.accept(sourceFrame, pkt, true)).andReturn(true);

        replayAll();

        bitstreamController.setTL0Idx(6, encodings);
        bitstreamController.setTargetIndex(8);

        assertTrue(bitstreamController.accept(sourceFrame, pkt, sourceStream));

        verifyAll();
    }

    @Test
    public void accept3()
        throws
        Exception
    {
        // State: Adaptive stream, have sent frame, currIndex = 8
        // Given: non key-frame from index 6, with a larger timestamp than the
        //  previous frame
        // Expected: should be accepted (newer frame from an adaptive stream
        //  for the same base layer)

        RTPEncodingDesc[] encodings = createEncodings(true);
        // We can re-use the same RawPacket
        RawPacket pkt = createPacket();
        // The source stream and a set of encodings for this stream
        MediaStream sourceStream = createMock(MediaStream.class);

        // We need to first get the BitstreamController in a state where it
        // has sent a frame, so we'll send an initial frame first, then
        // another frame to test the actual scenario
        long initialFrameTs = 1000;
        int initialFrameIndex = 8;
        int initialFrameMaxSeqNum = 10;
        FrameDesc initialFrame = createFrame(initialFrameIndex, encodings, initialFrameTs);

        // The SeenFrame for initialFrame
        SeenFrame initialSeenFrame = createMock(SeenFrame.class);
        expect(seenFrameAllocator.getOrCreate()).andReturn(initialSeenFrame);
        initialSeenFrame.reset(initialFrameTs, null, null, -1, -1, true, true);
        expectLastCall();
        expect(initialSeenFrame.getMaxSeqNum()).andReturn(initialFrameMaxSeqNum);
        expect(initialSeenFrame.accept(initialFrame, pkt, true)).andReturn(true);

        // The subsequent frame
        long frameTs = 1100;
        int frameIndex = 6;
        int frameSeqNumStart = initialFrameMaxSeqNum + 1;
        FrameDesc sourceFrame = createFrame(frameIndex, encodings, frameTs);
        expect(sourceFrame.getStart()).andReturn(frameSeqNumStart);

        // The SeenFrame for the subsequent frame
        SeenFrame seenFrame = createMock(SeenFrame.class);
        expect(seenFrameAllocator.getOrCreate()).andReturn(seenFrame);
        seenFrame.reset(
            eq(frameTs),
            anyObject(SeqNumTranslation.class),
            anyObject(TimestampTranslation.class),
            anyInt(),
            anyInt(),
            eq(true),
            eq(true));
        expectLastCall();
        expect(seenFrame.accept(sourceFrame, pkt, true)).andReturn(true);

        replayAll();

        bitstreamController.setTL0Idx(6, encodings);
        bitstreamController.setTargetIndex(8);

        assertTrue(bitstreamController.accept(initialFrame, pkt, sourceStream));
        assertTrue(bitstreamController.accept(sourceFrame, pkt, sourceStream));

        verifyAll();
    }
}