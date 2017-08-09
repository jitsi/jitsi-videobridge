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
import org.jitsi.service.neomedia.*;
import org.junit.*;
import org.junit.experimental.runners.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.junit.Assert.*;

@RunWith(Enclosed.class)
public class SeenFrameTest
    extends EasyMockSupport
{
    @RunWith(Parameterized.class)
    public static class SeenFrameAcceptTest
        extends EasyMockSupport
    {
        @Parameterized.Parameters
        public static Collection<Object[]> data()
        {
            return Arrays.asList(new Object[][] {
                //  frameMinSeen,   frameMaxSeen,   pktSeqNum,  isMostRecentFrame,  isAdaptive, expectedResult
                // Current frame, non-adaptive stream, sequence number in range
                {   5,              10,             7,          true,               false,      true },
                // Current frame, non-adaptive stream, sequence number not in range
                {   5,              10,             12,         true,               false,      false },
                // Current frame, adaptive stream, sequence number in range
                {   5,              10,             7,          true,               true,       true },
                // Current frame, adaptive stream, sequence number not in range
                {   5,              10,             12,         true,               true,       false },
                // Old frame, non-adaptive stream, sequence number in range
                {   5,              10,             7,          false,              false,      true },
                // Old frame, non-adaptive stream, sequence number not in range
                {   5,              10,             12,         false,              false,      false },
                // Old frame, adaptive stream, sequence number in range
                {   5,              10,             7,          false,              true,       false },
                // Old frame, adaptive stream, sequence number not in range
                {   5,              10,             12,         false,              true,       false },
            });
        }

        @Parameterized.Parameter(0)
        public int frameMinSeen;

        @Parameterized.Parameter(1)
        public int frameMaxSeen;

        @Parameterized.Parameter(2)
        public int pktSeqNum;

        @Parameterized.Parameter(3)
        public boolean isMostRecentFrame;

        @Parameterized.Parameter(4)
        public boolean isAdaptive;

        @Parameterized.Parameter(5)
        public boolean expectedResult;

        @Test
        public void accept()
        {
            FrameDesc sourceFrame = createMock(FrameDesc.class);
            RawPacket pkt = createMock(RawPacket.class);
            expect(sourceFrame.getMinSeen()).andReturn(frameMinSeen);
            expect(sourceFrame.getMaxSeen()).andReturn(frameMaxSeen);
            expect(pkt.getSequenceNumber()).andReturn(pktSeqNum);

            replayAll();

            SeenFrame seenFrame = new SeenFrame();
            seenFrame.reset(1000,
                null,
                null,
                0,
                0,
                false,
                isAdaptive);

            assertEquals(expectedResult, seenFrame.accept(sourceFrame, pkt, isMostRecentFrame));
        }
    }
}