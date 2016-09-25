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

package org.jitsi.videobridge.simulcast;

import org.easymock.*;
import static org.junit.Assert.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.jmfext.media.protocol.rtpdumpfile.*;
import org.jitsi.videobridge.*;
import org.junit.*;

import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * @author George Politis
 */
public class SimulcastReceiverTest
{
    private SimulcastEngine mockSimulcastEngine;

    @Before
    public void setUp()
        throws InterruptedException
    {
        // Setup mock objects.
        final byte REDPT = 0x74, VP8PT = 0x64;

        // Dummy SimulcastEngine. Doesn't do/have to do anything.
        VideoChannel mockVideoChannel
            = EasyMock.createMock(VideoChannel.class);

        //EasyMock.expect(mockVideoChannel.getRedPayloadType())
          //  .andReturn(REDPT)
            //.anyTimes();

        //EasyMock.expect(mockVideoChannel.getVP8PayloadType())
          //  .andReturn(VP8PT)
            //.anyTimes();
        EasyMock.replay(mockVideoChannel);

        mockSimulcastEngine
            = EasyMock.createMock(SimulcastEngine.class);

        EasyMock.expect(mockSimulcastEngine.getVideoChannel())
            .andReturn(mockVideoChannel)
            .anyTimes();
        EasyMock.replay(mockSimulcastEngine);

        // We needs guaranteed order of deliver of events.
        SimulcastReceiver.executorService
            = Executors.newSingleThreadExecutor();
    }

    /**
     *
     */
    static class ExpectedState
    {
        public ExpectedState(long ssrc, boolean streaming)
        {
            this.ssrc = ssrc;
            this.streaming = streaming;
        }

        long ssrc;
        boolean streaming;
    }

    /**
     *
     */
    class SimulcastReceiverListener implements SimulcastReceiver.Listener
    {
        public SimulcastReceiverListener(ExpectedState... expectedStates)
        {
            this.expectedStates = expectedStates;
        }

        ExpectedState[] expectedStates;

        int idxExpectedState = 0;

        int timesSimulcastStreamsSignaled = 0;

        @Override
        public void simulcastStreamsChanged(SimulcastStream... simulcastStreams)
        {
            for (int i = 0; i < simulcastStreams.length; i++)
            {
                assertEquals(
                    expectedStates[idxExpectedState++].streaming,
                    simulcastStreams[i].isStreaming);
            }
        }

        @Override
        public void simulcastStreamsSignaled()
        {
            timesSimulcastStreamsSignaled++;
        }
    }

    @Test
    public void simpleTest()
        throws Exception
    {
        // Setup the <tt>SimulcastReceiver</tt> to test.
        final SimulcastReceiver simulcastReceiver
            = new SimulcastReceiver(mockSimulcastEngine, null);

        final long[] ssrcs = new long[] {
            0xeb6717b9L, 0xa9245e9eL, 0xa7828c21L
        };

        ExpectedState[] expectedStates = new ExpectedState[] {
            new ExpectedState(ssrcs[1], true),
            new ExpectedState(ssrcs[2], true)
        };

        SimulcastReceiverListener listener
            = new SimulcastReceiverListener(expectedStates);
        simulcastReceiver.addWeakListener(
            new WeakReference<SimulcastReceiver.Listener>(listener));

        // "Signal" simulcast.
        simulcastReceiver.setSimulcastStreams(ssrcs);

        // Make sure the signaled event is fired.
        assertEquals(listener.timesSimulcastStreamsSignaled, 1);

        // Replay rtpdump contents.
        URL resourceUrl = getClass().getResource("simulcast-rtp.rtpdump");
        Path filePath = Paths.get(resourceUrl.toURI());
        RtpdumpFileReader rtpdumpFileReader
            = new RtpdumpFileReader(filePath.toString());

        try
        {
            RawPacket pkt;
            while ((pkt = rtpdumpFileReader.getNextPacket(false)) != null)
            {
                simulcastReceiver.accepted(pkt);
            }
        }
        catch (EOFException e)
        {
            // No more packets to read.
        }

        // Make sure the signaled event is fired only once.
        assertEquals(listener.timesSimulcastStreamsSignaled, 1);
    }
}
