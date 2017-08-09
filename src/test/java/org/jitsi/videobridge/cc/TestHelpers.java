package org.jitsi.videobridge.cc;

import org.easymock.*;
import org.jitsi.impl.neomedia.rtp.*;

import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;

public class TestHelpers
{
    private static void createAdaptiveStreamEncodingsHelper(
        int baseLayerIndex, RTPEncodingDesc[] encodings, int numAdaptiveLayers,
        long ssrc)
    {
        for (int i = baseLayerIndex; i < baseLayerIndex + numAdaptiveLayers; ++i)
        {
            RTPEncodingDesc desc = EasyMock.createMock(RTPEncodingDesc.class);
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

    /**
     * Create an array of RTPEncodingDesc mocks and return them.  Depending
     * on the value of isAdaptive, will create 3 independent encodings for
     * non-adaptive, or 3 independent encodings, each with 2 additional dependent
     * layers.
     * NOTE: The mocks in the array *must* be manually given to 'replay'
     * @param isAdaptive
     * @return
     */
    public static RTPEncodingDesc[] createEncodings(boolean isAdaptive)
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
}
