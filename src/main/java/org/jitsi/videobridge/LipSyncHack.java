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
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;

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
    implements TransformEngine,
               PacketTransformer
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
     * The rate (in ms) at which we are to send black key frames.
     */
    private static final long KEY_FRAME_RATE_MS = 33 /* 1000 ms / 30 */;

    /**
     * Timestamp increment per frame.
     */
    private static final long TS_INCREMENT_PER_FRAME
        = 90000 /* Hz */ / 30 /* fps */;

    /**
     * Wait for media for WAIT_MS before sending frames.
     */
    private static final long WAIT_MS = 2000;

    /**
     * The <tt>Logger</tt> used by the <tt>LipSyncHack</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(LipSyncHack.class);

    /**
     * The value of {@link Logger#isWarnEnabled()} from the time of the
     * initialization of the class {@code LipSyncHack} cached for the purposes
     * of performance.
     */
    private static final boolean DEBUG = logger.isDebugEnabled();

    /**
     * The {@link Random} that will be used to generate the random sequence
     * number and RTP timestamp offsets.
     */
    private static final Random RANDOM = new Random();

    /**
     * The owner of this hack.
     */
    private final VideoChannel channel;

    /**
     * The executor service that takes care of black key frame scheduling
     * and injection.
     */
    private final RecurringRunnableExecutor scheduler =
        new RecurringRunnableExecutor();

    /**
     * The remote audio SSRCs that have been accepted by the translator and
     * forwarded to the endpoint associated to this instance.
     */
    private final List<Long> acceptedAudioSSRCs = new ArrayList<>();

    /**
     * The remote video SSRCs that have been accepted by the translator and
     * forwarded to the endpoint associated to this instance.
     */
    private final List<Long> acceptedVideoSSRCsRTCP = new ArrayList<>();


    /**
     * The remote video SSRCs that have been accepted by the translator and
     * forwarded to the endpoint associated to this instance.
     */
    private final List<Long> acceptedVideoSSRCsRTP = new ArrayList<>();

    /**
     * The collection of SSRCs for which we haven't sent out black VP8 key
     * frames. Access to the list needs to be thread. We expect far less writes
     * than reads thus we use a {@link CopyOnWriteArrayList}.
     */
    private final Collection<Long> ssrcsWithoutBlackKeyframes
        = new CopyOnWriteArrayList<>();

    /**
     * A map that holds all the inject states.
     */
    private final Map<Long, InjectState> states = new HashMap<>();

    /**
     * Ctor.
     *
     * @param channel the {@link VideoChannel} that owns this hack.
     */
    public LipSyncHack(VideoChannel channel)
    {
        this.channel = channel;
    }

    /**
     * Notifies this instance that an audio packet (RTP or RTCP) is about to be
     * written.
     *
     * @param data true if the buffer holds an RTP packet, false otherwise.
     * @param buffer the buffer which contains the bytes of the received RTP or
     * RTCP packet.
     * @param offset the zero-based index in <tt>buffer</tt> at which the bytes
     * of the received RTP or RTCP packet begin.
     * @param length the number of bytes in <tt>buffer</tt> beginning at
     * <tt>offset</tt> which represent the received RTP or RTCP packet.
     * @param source the {@link Channel} where this packet came from.
     */
    public void onRTPTranslatorWillWriteAudio(
        boolean data, byte[] buffer, int offset,
        int length, Channel source)
    {
        if (!data)
        {
            return;
        }

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

        List<RtpChannel> sourceVideoChannels
            = source.getEndpoint().getChannels(MediaType.VIDEO);
        if (sourceVideoChannels == null || sourceVideoChannels.size() == 0)
        {
            return;
        }

        VideoChannel sourceVideoChannel
            = (VideoChannel) sourceVideoChannels.get(0);
        if (sourceVideoChannel == null)
        {
            return;
        }

        MediaStream sourceVideoStream = sourceVideoChannel.getStream();
        if (sourceVideoStream == null)
        {
            return;
        }

        MediaStreamTrackReceiver sourceReceiver
            = sourceVideoStream.getMediaStreamTrackReceiver();

        if (sourceReceiver == null)
        {
            return;
        }

        MediaStreamTrack[] sourceTracks = sourceReceiver.getMediaStreamTracks();
        if (ArrayUtils.isNullOrEmpty(sourceTracks))
        {
            return;
        }

        RTPEncoding[] sourceEncodings = sourceTracks[0].getRTPEncodings();
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

        synchronized (states)
        {
            if (states.containsKey(receiveVideoSSRC))
            {
                // This receive video SSRC has already been processed.
                return;
            }

            InjectState injectState = new InjectState(receiveVideoSSRC, true);

            states.put(receiveVideoSSRC, injectState);

            InjectTask injectTask = new InjectTask(injectState);
            scheduler.registerRecurringRunnable(injectTask);

            if (DEBUG)
            {
                logger.debug("ls_hack_register,ssrc=" + injectState.ssrc);
            }
        }
    }

    /**
     * Notifies this instance that a video packet (RTP or RTCP) is about to be
     * written.
     *
     * @param data true if the buffer holds an RTP packet, false otherwise.
     * @param buffer the buffer which contains the bytes of the received RTP or
     * RTCP packet.
     * @param offset the zero-based index in <tt>buffer</tt> at which the bytes
     * of the received RTP or RTCP packet begin.
     * @param length the number of bytes in <tt>buffer</tt> beginning at
     * <tt>offset</tt> which represent the received RTP or RTCP packet.
     * @param target the {@link Channel} where this packet is going.
     */
    public void onRTPTranslatorWillWriteVideo(
        boolean accept, boolean data, byte[] buffer,
        int offset, int length, Channel target)
    {
        if (!accept)
        {
            return;
        }

        Long acceptedVideoSSRC /* box early */;
        long timestamp;
        int seqnum;

        if (data)
        {
            acceptedVideoSSRC
                = RawPacket.getSSRCAsLong(buffer, offset, length);

            // In order to minimize the synchronization overhead, we process
            // only the first data packet of a given RTP stream.
            //
            // XXX No synchronization is required to r/w the acceptedVideoSSRCs
            // because in the current architecture this method is called by a
            // single thread at the time.
            if (acceptedVideoSSRCsRTP.contains(acceptedVideoSSRC))
            {
                return;
            }

            acceptedVideoSSRCsRTP.add(acceptedVideoSSRC);

            timestamp
                = RawPacket.getTimestamp(buffer, offset, length);

            seqnum
                = RawPacket.getSequenceNumber(buffer, offset, length);
        }
        else
        {
            // The correct thing to do here is a loop because the RTCP packet
            // can be compound. However, in practice we haven't seen multiple
            // SRs being bundled in the same compound packet, and we're only
            // interested in SRs.

            // Check RTCP packet validity. This makes sure that pktLen > 0
            // so this loop will eventually terminate.
            if (!RTCPHeaderUtils.isValid(buffer, offset, length))
            {
                return;
            }

            int pktLen = RTCPHeaderUtils.getLength(buffer, offset, length);

            int pt = RTCPHeaderUtils.getPacketType(buffer, offset, pktLen);
            if (pt == RTCPPacket.SR)
            {
                acceptedVideoSSRC
                    = RTCPHeaderUtils.getSenderSSRC(buffer, offset, pktLen);

                // In order to minimize the synchronization overhead, we process
                // only the first data packet of a given RTP stream.
                //
                // XXX No synchronization is required to r/w the acceptedVideoSSRCs
                // because in the current architecture this method is called by
                // a single thread at the time.
                if (acceptedVideoSSRCsRTCP.contains(acceptedVideoSSRC))
                {
                    return;
                }

                acceptedVideoSSRCsRTCP.add(acceptedVideoSSRC);

                timestamp = RTCPSenderInfoUtils.getTimestamp(
                    buffer, offset + RTCPHeader.SIZE, pktLen - RTCPHeader.SIZE);

                seqnum = -1;
            }
            else
            {
                return;
            }
        }

        final VideoChannel targetVC = (VideoChannel) target;
        InjectState state;
        synchronized (states)
        {
            state = states.get(acceptedVideoSSRC);
            if (state == null)
            {
                // The hack has never been triggered for this stream.
                states.put(acceptedVideoSSRC, new InjectState(acceptedVideoSSRC,
                    false));

                return;
            }
        }

        synchronized (state)
        {
            // If we reached this point => state.active = true.
            state.active = false;

            if (state.numOfKeyframesSent == 0)
            {
                // No key frames have been sent for this SSRC => No need to
                // rewrite anything.
                ssrcsWithoutBlackKeyframes.add(acceptedVideoSSRC);
                return;
            }

            StreamRTPManager streamRTPManager
                = targetVC.getStream().getStreamRTPManager();

            ResumableStreamRewriter rewriter = streamRTPManager
                .getResumableStreamRewriter(acceptedVideoSSRC);

            if (timestamp != -1)
            {
                // Timestamps are calculated.
                long highestTimestampSent = state.getNextTimestamp();

                long lastTimestampDropped
                    = (timestamp - TS_INCREMENT_PER_FRAME) & 0xffffffffl;
                long timestampDelta =
                    (lastTimestampDropped - highestTimestampSent) & 0xffffffffl;

                // timestamps might have already been updated, due to the
                // reception of RTCP.
                if (rewriter.getHighestTimestampSent() == -1)
                {
                    rewriter.setHighestTimestampSent(highestTimestampSent);
                }

                if (rewriter.getTimestampDelta() == 0)
                {
                    rewriter.setTimestampDelta(timestampDelta);
                }
            }

            if (seqnum != -1)
            {
                // Pretend we have dropped all the packets prior to the one
                // that's about to be written by the translator.
                int highestSeqnumSent = state.getNextSequenceNumber();

                // Pretend we have dropped all the packets prior to the one
                // that's about to be written by the translator.
                int lastSeqnumDropped = RTPUtils.subtractNumber(seqnum, 1);
                int seqnumDelta = RTPUtils.subtractNumber(
                    lastSeqnumDropped, highestSeqnumSent);

                rewriter.setHighestSequenceNumberSent(highestSeqnumSent);
                rewriter.setSeqnumDelta(seqnumDelta);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return null;
    }

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
        // If a packet needs to be prepended, then its SSRC needs to be in
        // ssrcsWithoutBlackKeyframes already.
        if (pkts == null || pkts.length == 0
            || ssrcsWithoutBlackKeyframes.isEmpty())
        {
            return pkts;
        }

        RawPacket[] extra = null;

        for (int i = 0; i < pkts.length; i++)
        {
            if (pkts[i] == null)
            {
                continue;
            }

            long ssrc = pkts[i].getSSRCAsLong();
            if (ssrcsWithoutBlackKeyframes.contains(ssrc))
            {
                ssrcsWithoutBlackKeyframes.remove(ssrc);

                StreamRTPManager receiveRTPManager = channel
                    .getStream()
                    .getRTPTranslator()
                    .findStreamRTPManagerByReceiveSSRC((int) ssrc);

                MediaStreamTrackReceiver receiver = null;
                if (receiveRTPManager != null)
                {
                    MediaStream receiveStream
                        = receiveRTPManager.getMediaStream();
                    if (receiveStream != null)
                    {
                        receiver = receiveStream.getMediaStreamTrackReceiver();
                    }
                }

                if (receiver == null)
                {
                    continue;
                }

                FrameDesc frameDesc
                    = receiver.resolveFrameDesc(pkts[i]);

                boolean isSOF
                    = frameDesc.getStart() == pkts[i].getSequenceNumber();

                int sofDistance = isSOF ? 0 : 10;

                int seqNum = pkts[i].getSequenceNumber();
                long ts = pkts[i].getTimestamp();
                RawPacket[] kfs = new RawPacket[MAX_KEY_FRAMES];
                for (int j = 0; j < kfs.length; j++)
                {
                    int relativeIdx = j - kfs.length - sofDistance;
                    byte[] buf = KEY_FRAME_BUFFER.clone();
                    RawPacket kf = new RawPacket(buf, 0, buf.length);

                    // Set SSRC.
                    kf.setSSRC((int) ssrc);

                    // Set sequence number.
                    int seqnum = (seqNum + relativeIdx) & 0xFFFF;
                    kf.setSequenceNumber(seqnum);

                    // Set RTP timestamp.
                    long timestamp = ts + relativeIdx * TS_INCREMENT_PER_FRAME;
                    kf.setTimestamp(timestamp);
                    kfs[j] = kf;
                }

                extra = ArrayUtils.concat(extra, kfs);
            }
        }

        if (extra != null && extra.length != 0)
        {
            RawPacket[] ret = new RawPacket[extra.length + pkts.length];
            System.arraycopy(extra, 0, ret, 0, extra.length);
            System.arraycopy(pkts, 0, ret, extra.length - 1, pkts.length);
            return ret;
        }
        else
        {
            return pkts;
        }
    }

    /**
     * The {@link Runnable} that injects the black video key frame packets.
     */
    class InjectTask implements RecurringRunnable
    {
        /**
         * The state for this injector.
         */
        private final InjectState injectState;

        /**
         * The last time in miliseconds that this task has run.
         */
        private long lastRunTime = -1L;

        /**
         * Ctor.
         *
         * @param injectState
         */
        public InjectTask(InjectState injectState)
        {
            this.injectState = injectState;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run()
        {
            synchronized (injectState)
            {
                if (!injectState.active
                    || injectState.numOfKeyframesSent >= MAX_KEY_FRAMES)
                {
                    deregister("completed");
                    return;
                }

                MediaStream mediaStream = channel.getStream();
                if (mediaStream == null || !mediaStream.isStarted())
                {
                    deregister("stream_unavailable");
                    return;
                }

                lastRunTime = System.currentTimeMillis();

                try
                {
                    injectState.numOfKeyframesSent++;

                    // FIXME maybe grab from the write pool and copy the array?
                    byte[] buf = KEY_FRAME_BUFFER.clone();
                    RawPacket keyframe = new RawPacket(buf, 0, buf.length);

                    // Set SSRC.
                    keyframe.setSSRC(injectState.ssrc.intValue());

                    // Set sequence number.
                    int seqnum = injectState.getNextSequenceNumber();
                    keyframe.setSequenceNumber(seqnum);

                    // Set RTP timestamp.
                    long timestamp = injectState.getNextTimestamp();
                    keyframe.setTimestamp(timestamp);

                    if (DEBUG)
                    {
                        logger.debug("ls_hack_inject,"
                            + "ssrc=" + injectState.ssrc
                            + ",hash=" + mediaStream.hashCode()
                            + " seqnum=" + seqnum
                            + ",ts=" + timestamp);
                    }

                    mediaStream.injectPacket(keyframe, true, null);
                }
                catch (TransmissionFailedException e)
                {
                    injectState.numOfKeyframesSent--;
                    logger.error(e);
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public long getTimeUntilNextRun()
        {
            synchronized (injectState)
            {
                if (!injectState.active
                    || injectState.numOfKeyframesSent >= MAX_KEY_FRAMES)
                {
                    deregister("complete");
                    return Long.MAX_VALUE;
                }

                // send 3 "waves" of 10 packet bursts every 1 second. This makes
                // sure that the signaling has propagated and that eventually a
                // packet gets through SRTP.
                long delay = 0;
                if (injectState.numOfKeyframesSent % 10 == 0)
                {
                    delay = WAIT_MS;
                }

                long timeUntilNextRun =  lastRunTime
                    + KEY_FRAME_RATE_MS + delay - System.currentTimeMillis();

                if (DEBUG)
                {
                    logger.debug(
                        "ls_hack_schedule timeUntilNextRun=" + timeUntilNextRun);
                }

                return timeUntilNextRun;
            }
        }

        /**
         * De-registers this {@code RecurringRunnable} and optionally prints a
         * debug message.
         *
         * @param reason the de-registration reason
         */
        private void deregister(String reason)
        {
            if (DEBUG)
            {
                logger.debug("ls_hack_deregister"
                    + ",ssrc=" + injectState.ssrc
                    + " reason=" + reason);
            }

            scheduler.deRegisterRecurringRunnable(this);
        }
    }

    /**
     * The RTP state of every monitored video SSRC.
     */
    static class InjectState
    {
        /**
         * The SSRC to send black key frames with.
         */
        private final Long ssrc;

        /**
         * The random offset for the sequence numbers.
         */
        private final int seqnumOffset;

        /**
         * The random offset for the RTP timestamps.
         */
        private final long timestampOffset;

        /**
         * True if no real video packets have been received for this SSRC, false
         * otherwise.
         */
        private boolean active;

        /**
         * The number of key frames that have already been sent.
         */
        private int numOfKeyframesSent = 0;

        /**
         * Gets the next sequence number to use based on the number of key
         * frames that have already been sent.
         *
         * @return the next sequence number to use based on the number of key
         * frames that have already been sent.
         */
        public int getNextSequenceNumber()
        {
            return (seqnumOffset + numOfKeyframesSent) & 0xffff;
        }

        /**
         * Gets the next timestamp to use based on the number of key frames
         * that have already been sent.
         *
         * @return Gets the next timestamp to use based on the number of key
         * frames that have already been sent.
         */
        public long getNextTimestamp()
        {
            return (timestampOffset
                + numOfKeyframesSent * TS_INCREMENT_PER_FRAME) & 0xffffffffl;
        }

        /**
         * Ctor.
         *
         * @param ssrc
         */
        public InjectState(Long ssrc, boolean active)
        {
            this.ssrc = ssrc;
            this.active = active;
            this.seqnumOffset = RANDOM.nextInt(0xffff);
            this.timestampOffset = RANDOM.nextInt() & 0xffffffffl;
        }
    }
}
