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
package org.jitsi.videobridge;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Implements a hack for
 * https://bugs.chromium.org/p/chromium/issues/detail?id=403710. The hack
 * injects black video (VP8) key frames to unstuck the playback of audio for
 * composite media streams.
 *
 * @author George Politis
 */
public class LipSyncHack
    implements TransformEngine
{
    /**
     * A byte array holding a black VP8 key frame. The byte array contains the
     * full RTP packet and not just the VP8 payload.
     */
    private static final byte[] KEY_FRAME_BUFFER = new byte[]{ -112, -28,
        64, 52,
        -92, -96, 115, -79, -5, -111, 32, 79, -66, -34, 0, 1, 50, -63, 45, -124,
        -112, -32, -3, 48, -17, 32, 16, 18, 0, -99, 1, 42, 64, 1, -76, 0, 57,
        75, 0, 27, 28, 36, 12, 44, 44, 68, -52, 36, 65, 36, 1, 18, 76, 28, -95,
        -109, 56, 60, 9, -105, 79, 38, -65, -37, -38, 32, -43, 37, -111, 4, -93,
        68, 49, -67, -94, 13, -115, -45, 44, -110, 95, -61, 27, -38, 32, -40,
        -35, -104, 123, -13, -109, 95, -19, -19, 16, 108, 110, -48, 63, 34, 13,
        -115, -38, 18, -105, 63, 68, 49, -67, -95, -26, -101, 48, -9, -25, 38,
        -19, 9, 75, -98, -86, -35, 50, -23, -31, 37, -111, 11, 39, -110, 82,
        -108, 54, -115, -115, -38, 17, -60, 104, -122, 55, -79, 13, 55, 104, 74,
        92, -3, 16, -58, -10, -120, 54, 55, 104, 74, 92, -4, 122, 109, 9, 75,
        -98, -86, -35, 50, -55, -103, -126, 82, -105, 63, 68, 49, -68, 89, -55,
        -69, 46, 0, -2, -78, 38, 50, -16, 47, -126, -99, -32, 50, 32, 67, 100,
        0};

    /**
     * A constant defining the maximum number of black key frames to send.
     */
    private static final int MAX_KEY_FRAMES = 10;

    /**
     * Timestamp increment per frame.
     */
    private static final long TS_INCREMENT_PER_FRAME
        = 90000 /* Hz */ / 30 /* fps */;

    /**
     * The <tt>Logger</tt> used by the <tt>LipSyncHack</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(LipSyncHack.class);

    /**
     * The {@link Random} that will be used to generate the random sequence
     * number and RTP timestamp offsets.
     */
    private static final Random rnd = new Random();

    /**
     * The {@link PacketTransformer} that rewrites RTP or prepends RTP streams
     * with black key frames.
     */
    private final RTPTransformer rtpTransformer = new RTPTransformer();

    /**
     * The {@link PacketTransformer} that rewrites RTCP.
     */
    private final RTCPTransformer rtcpTransformer = new RTCPTransformer();

    /**
     * The owner of this hack.
     */
    private final VideoChannel channel;

    /**
     * The remote audio SSRCs that have been accepted by the translator and
     * forwarded to the endpoint associated to this instance.
     */
    private final List<Long> acceptedAudioSSRCs = new ArrayList<>();

    /**
     * The remote video SSRCs that have been accepted by the translator and
     * forwarded to the endpoint associated to this instance.
     */
    private final List<Long> acceptedVideoSSRCs = new ArrayList<>();

    /**
     * The sync root to synchronize the filter threads.
     */
    private final Object filterSyncRoot = new Object();

    /**
     * The {@link Map} of SSRCs for which we've sent out black VP8 key frames.
     */
    private final Map<Long, Injection> injections
        = new HashMap<>();

    /**
     * The {@link Map} of transformations necessary per SSRCs.
     */
    private final Map<Long, Transformation> transformations
        = new ConcurrentHashMap<>();

    /**
     * Ctor.
     *
     * @param channel the {@link VideoChannel} that owns this hack.
     */
    LipSyncHack(VideoChannel channel)
    {
        this.channel = channel;
    }

    /**
     * Notifies this instance that an audio packet (RTP or RTCP) is about to be
     * written.
     *
     * @param buffer the buffer which contains the bytes of the received RTP or
     * RTCP packet.
     * @param offset the zero-based index in <tt>buffer</tt> at which the bytes
     * of the received RTP or RTCP packet begin.
     * @param length the number of bytes in <tt>buffer</tt> beginning at
     * <tt>offset</tt> which represent the received RTP or RTCP packet.
     * @param source the {@link Channel} where this packet came from.
     */
    void onRTPTranslatorWillWriteAudio(
        byte[] buffer, int offset,
        int length, Channel source)
    {
        // Decide whether to trigger the hack or not.
        Long acceptedAudioSSRC
            = RawPacket.getSSRCAsLong(buffer, offset, length);

        // In order to minimize the synchronization overhead, we process
        // only the first data packet of a given RTP stream.
        //
        // XXX No synchronization is required to r/w the acceptedAudioSSRCs
        // because in the current architecture this method is called by a single
        // thread at the time.
        if (acceptedAudioSSRCs.contains(acceptedAudioSSRC))
        {
            // We've already triggered the hack for this audio stream and its
            // associated video streams.
            return;
        }

        // New audio stream. Trigger the hack for the associated video stream.
        MediaStream stream;
        if (channel == null || (stream = channel.getStream()) == null
            || !stream.isStarted())
        {
            // It seems like we're not ready yet to trigger the hack.
            return;
        }

        MediaStreamTrackDesc[] sourceTracks
            = source.getEndpoint().getMediaStreamTracks(MediaType.VIDEO);
        if (ArrayUtils.isNullOrEmpty(sourceTracks))
        {
            return;
        }

        RTPEncodingDesc[] sourceEncodings = sourceTracks[0].getRTPEncodings();
        if (ArrayUtils.isNullOrEmpty(sourceEncodings))
        {
            return;
        }

        long receiveVideoSSRC = sourceEncodings[0].getPrimarySSRC();

        // XXX we do this here (i.e. below the sanity checks), in order to avoid
        // any race conditions with a video channel being created and added to
        // its Endpoint. The disadvantage being that endpoints that only have an
        // audio channel will never reach this.
        acceptedAudioSSRCs.add(acceptedAudioSSRC);

        synchronized (filterSyncRoot)
        {
            if (acceptedVideoSSRCs.contains(receiveVideoSSRC)
                || injections.containsKey(receiveVideoSSRC))
            {
                // No need to hack this SSRC.
                return;
            }

            int seqNumOff = rnd.nextInt(0xFFFF);
            long tsOff = rnd.nextInt() & 0xFFFFFFFFL;

            RawPacket[] kfs = make((int) receiveVideoSSRC, seqNumOff, tsOff);
            for (int i = 0; i < kfs.length; i++)
            {
                try
                {
                    stream.injectPacket(kfs[i], true, this);
                }
                catch (TransmissionFailedException e)
                {
                    logger.error("failed to inject a black keyframe.", e);
                }
            }

            injections.put(receiveVideoSSRC,
                new Injection((seqNumOff + MAX_KEY_FRAMES) & 0xFFFF,
                    (tsOff + MAX_KEY_FRAMES * TS_INCREMENT_PER_FRAME)
                        & 0xFFFFFFFFL));
        }
    }

    /**
     *
     * @param ssrc
     * @param seqNumOff
     * @param tsOff
     * @return
     */
    private RawPacket[] make(int ssrc, int seqNumOff, long tsOff)
    {
        RawPacket[] kfs = new RawPacket[MAX_KEY_FRAMES];
        for (int i = 0; i < MAX_KEY_FRAMES; i++)
        {
            // FIXME maybe grab from the write pool and copy the array?
            byte[] buf = KEY_FRAME_BUFFER.clone();
            kfs[i] = new RawPacket(buf, 0, buf.length);

            // Set SSRC.
            kfs[i].setSSRC(ssrc);

            // Set sequence number.
            int seqnum = (seqNumOff + i) & 0xFFFF;
            kfs[i].setSequenceNumber(seqnum);

            // Set RTP timestamp.
            long timestamp = (tsOff + i * 3000) & 0xFFFFFFFFL;
            kfs[i].setTimestamp(timestamp);
        }

        return kfs;
    }

    /**
     * Notifies this instance that a video packet (RTP or RTCP) is about to be
     * written.
     *
     * @param buffer the buffer which contains the bytes of the received RTP or
     * RTCP packet.
     * @param offset the zero-based index in <tt>buffer</tt> at which the bytes
     * of the received RTP or RTCP packet begin.
     * @param length the number of bytes in <tt>buffer</tt> beginning at
     * <tt>offset</tt> which represent the received RTP or RTCP packet.
     */
    void onRTPTranslatorWillWriteVideo(byte[] buffer, int offset, int length)
    {
        Long acceptedVideoSSRC
            = RawPacket.getSSRCAsLong(buffer, offset, length);

        if (acceptedVideoSSRCs.contains(acceptedVideoSSRC))
        {
            return;
        }

        synchronized (filterSyncRoot)
        {
            acceptedVideoSSRCs.add(acceptedVideoSSRC);

            Injection injectState
                = injections.get(acceptedVideoSSRC);

            if (injectState != null)
            {
                int seqNum = RawPacket.getSequenceNumber(buffer, offset, length);
                long ts = RawPacket.getTimestamp(buffer, offset, length);

                int seqNumDelta
                    = (injectState.maxSeqNum + 10 - seqNum) & 0xFFFF;
                long tsDelta
                    = (injectState.maxTs + 10 * 3000 - ts) & 0xFFFFFFFFL;

                transformations.put(acceptedVideoSSRC,
                    new Transformation(tsDelta, seqNumDelta));
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return rtpTransformer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return rtcpTransformer;
    }

    /**
     *
     */
    private class RTPTransformer
        implements PacketTransformer
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public void close()
        {

        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] reverseTransform(RawPacket[] pkts)
        {
            return pkts;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] transform(RawPacket[] pkts)
        {
            if (ArrayUtils.isNullOrEmpty(pkts))
            {
                return pkts;
            }

            RawPacket[] cumulExtras = null;
            for (int i = 0; i < pkts.length; i++)
            {
                if (pkts[i] == null
                    || !RTPPacketPredicate.INSTANCE.test(pkts[i]))
                {
                    continue;
                }

                Long ssrc = pkts[i].getSSRCAsLong();
                Transformation state = transformations.get(ssrc);
                if (state == null)
                {
                    // Prepend.
                    int seqNumOff = (pkts[i].getSequenceNumber() - 10) & 0xFFFF;
                    long tsOff = (pkts[i].getTimestamp() - 10 * 3000) & 0xFFFFFFFFL;
                    RawPacket[] extras = make(ssrc.intValue(), seqNumOff, tsOff);
                    cumulExtras = ArrayUtils.concat(cumulExtras, extras);
                    state = new Transformation(0, 0);
                    transformations.put(ssrc, state);
                }
                else
                {
                    int srcSeqNum = pkts[i].getSequenceNumber();
                    int dstSeqNum = state.rewriteSeqNum(srcSeqNum);

                    long srcTs = pkts[i].getTimestamp();
                    long dstTs = state.rewriteTimestamp(srcTs);

                    if (srcSeqNum != dstSeqNum)
                    {
                        pkts[i].setSequenceNumber(dstSeqNum);
                    }

                    if (dstTs != srcTs)
                    {
                        pkts[i].setTimestamp(dstTs);
                    }
                }
            }

            return ArrayUtils.concat(cumulExtras, pkts);
        }
    }

    /**
     *
     */
    private class RTCPTransformer
        implements PacketTransformer
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public void close()
        {

        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] reverseTransform(RawPacket[] pkts)
        {
            return pkts;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] transform(RawPacket[] pkts)
        {
            if (ArrayUtils.isNullOrEmpty(pkts) || injections.isEmpty())
            {
                return pkts;
            }

            return translate(pkts);
        }

        private RawPacket[] translate(RawPacket[] pkts)
        {
            for (int i = 0; i < pkts.length; i++)
            {
                if (pkts[i] == null
                    || !RTCPPacketPredicate.INSTANCE.test(pkts[i]))
                {
                    continue;
                }

                RTCPIterator it = new RTCPIterator(pkts[i]);
                while (it.hasNext())
                {
                    ByteArrayBuffer baf = it.next();
                    switch (RTCPHeaderUtils.getPacketType(baf))
                    {
                    case RTCPPacket.SR:
                        long ssrc = RawPacket.getRTCPSSRCAsLong(baf);
                        if (transformations.containsKey(ssrc))
                        {
                            Transformation state
                                = transformations.get(ssrc);

                            // Rewrite timestamp.
                            long srcTs = RTCPSenderInfoUtils.getTimestamp(baf);
                            long dstTs = state.rewriteTimestamp(srcTs);

                            if (srcTs != dstTs)
                            {
                                RTCPSenderInfoUtils.setTimestamp(baf, dstTs);
                            }
                        }
                    }
                }
            }

            return pkts;
        }
    }

    /**
     *
     */
    private static class Injection
    {
        /**
         * Ctor.
         *
         * @param maxSeqNum
         * @param maxTs
         */
        public Injection(int maxSeqNum, long maxTs)
        {
            this.maxSeqNum = maxSeqNum;
            this.maxTs = maxTs;
        }

        /**
         *
         */
        final int maxSeqNum;

        /**
         *
         */
        final long maxTs;
    }
}
