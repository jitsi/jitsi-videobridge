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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import static org.easymock.EasyMock.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.impl.neomedia.jmfext.media.protocol.rtpdumpfile.*;
import org.jitsi.impl.neomedia.transform.rewriting.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.transform.*;
import org.junit.*;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;

/**
 * @author George Politis
 */
public class SimulcastTest
{
    @Before
    public void setUp()
        throws InterruptedException
    {
    }

    class TransformEngineChain
        implements TransformEngine
    {
        public TransformEngineChain(TransformEngine[] engineChain)
        {
            this.engineChain = engineChain;
        }

        private final TransformEngine[] engineChain;

        @Override
        public PacketTransformer getRTPTransformer()
        {
            return new PacketTransformer()
            {
                @Override
                public RawPacket[] reverseTransform(RawPacket[] pkts)
                {
                    for (int i = engineChain.length - 1 ; i >= 0; i--)
                    {
                        pkts = engineChain[i]
                            .getRTPTransformer().reverseTransform(pkts);
                    }

                    return pkts;
                }

                @Override
                public RawPacket[] transform(RawPacket[] pkts)
                {
                    for (int i = 0; i < engineChain.length; i++)
                    {
                        pkts = engineChain[i]
                            .getRTPTransformer().transform(pkts);
                    }

                    return pkts;
                }

                @Override
                public void close()
                {

                }
            };
        }

        @Override
        public PacketTransformer getRTCPTransformer()
        {
            return new PacketTransformer()
            {
                @Override
                public RawPacket[] reverseTransform(RawPacket[] pkts)
                {
                    for (int i = engineChain.length - 1 ; i >= 0; i--)
                    {
                        pkts = engineChain[i]
                            .getRTCPTransformer().reverseTransform(pkts);
                    }

                    return pkts;
                }

                @Override
                public RawPacket[] transform(RawPacket[] pkts)
                {
                    for (int i = 0; i < engineChain.length; i++)
                    {
                        pkts = engineChain[i]
                            .getRTCPTransformer().transform(pkts);
                    }

                    return pkts;
                }

                @Override
                public void close()
                {

                }
            };
        }
    }

    @Test
    public void simpleTest()
        throws Exception
    {
        final long[] ssrcs = new long[] {
            0xeb6717b9L, 0xa9245e9eL, 0xa7828c21L
        };

        final byte REDPT = 0x74, VP8PT = 0x64;

        // Prepare the mock objects.
        Content cnt = createMock(Content.class);

        // Prepare the sender.
        RtpChannelTransformEngine sendTE
            = createMock(RtpChannelTransformEngine.class);
        MediaStream sendStream = createMock(MediaStream.class);
        Endpoint recvEndpoint = createMock(Endpoint.class);
        recvEndpoint.addPropertyChangeListener(
            anyObject(PropertyChangeListener.class));
        expectLastCall().anyTimes();
        VideoChannel sendViC = createMock(VideoChannel.class);
        //expect(sendViC.getRedPayloadType()).andReturn(REDPT).anyTimes();
        //expect(sendViC.getVP8PayloadType()).andReturn(VP8PT).anyTimes();
        expect(sendViC.getContent()).andReturn(cnt).anyTimes();
        expect(sendViC.getSimulcastMode())
            .andReturn(SimulcastMode.REWRITING)
            .anyTimes();
        expect(sendViC.getTransformEngine()).andReturn(sendTE).anyTimes();
        expect(sendViC.getEndpoint()).andReturn(recvEndpoint).anyTimes();
        sendViC.addPropertyChangeListener(
            anyObject(PropertyChangeListener.class));
        expectLastCall().anyTimes();

        SimulcastEngine sendSE = new SimulcastEngine(sendViC);
        expect(sendTE.getSimulcastEngine())
            .andReturn(sendSE)
            .anyTimes();

        //BasicRTCPTerminationStrategy sendBRTS
          //  = new BasicRTCPTerminationStrategy();
        //sendBRTS.initialize(sendStream);
        TransformEngine sendEngine = new TransformEngineChain(
            new TransformEngine[]
            {
                sendSE,
                new SsrcRewritingEngine(sendStream),
          //      sendBRTS,
            });

        // Prepare the receiver.
        RtpChannelTransformEngine recvTE
            = createMock(RtpChannelTransformEngine.class);
        MediaStream recvStream
            = createMock(MediaStream.class);

        VideoChannel recvViC
            = createMock(VideoChannel.class);
        recvViC.askForKeyframes(isA(int[].class));
        expectLastCall().anyTimes();
        Endpoint sendEndpoint = createMock(Endpoint.class);
        expect(recvViC.getEndpoint()).andReturn(sendEndpoint).anyTimes();
        //expect(recvViC.getRedPayloadType())
          //  .andReturn(REDPT)
            //.anyTimes();
        //expect(recvViC.getVP8PayloadType())
          //  .andReturn(VP8PT)
            //.anyTimes();
        expect(recvViC.getContent())
            .andReturn(cnt)
            .anyTimes();
        expect(recvViC.getTransformEngine())
            .andReturn(recvTE)
            .anyTimes();

        SimulcastEngine recvSE = new SimulcastEngine(recvViC);
        expect(recvTE.getSimulcastEngine())
            .andReturn(recvSE)
            .anyTimes();

        //BasicRTCPTerminationStrategy recvBRTS
          //  = new BasicRTCPTerminationStrategy();
       // recvBRTS.initialize(recvStream);
        TransformEngine recvEngine = new TransformEngineChain(
            new TransformEngine[]
            {
                recvSE,
                new SsrcRewritingEngine(recvStream),
         //       recvBRTS,
            });

        recvSE.getSimulcastReceiver().setSimulcastStreams(ssrcs);

        // Configure the mock content.
        for (int i = 0; i < ssrcs.length; i++)
        {
            expect(cnt.findChannel(ssrcs[i]))
                .andReturn(recvViC)
                .anyTimes();
        }

        replay(sendTE, sendViC, sendStream, recvEndpoint,
            recvTE, recvViC, recvStream, sendEndpoint, cnt);

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
                sendEngine.getRTPTransformer()
                    .transform(recvEngine.getRTPTransformer()
                    .reverseTransform(new RawPacket[] { pkt }));
            }
        }
        catch (EOFException e)
        {
            // No more packets to read.
        }
    }
}
