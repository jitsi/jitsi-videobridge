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

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Implements a hack for
 * https://bugs.chromium.org/p/chromium/issues/detail?id=403710. The hack
 * injects black video key frames to unstuck the playback of audio for composite
 * media streams.
 *
 * @author George Politis
 */
public class LipSyncHack
{
    /**
     * A byte array holding a black key frame.
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
    private static final int MAX_KEY_FRAMES = 20;

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
    private static final long WAIT_MS = 1000;

    /**
     * The <tt>Logger</tt> used by the <tt>LipSyncHack</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(LipSyncHack.class);

    /**
     * The owner of this hack.
     */
    private final Endpoint endpoint;

    /**
     * The executor service that takes care of black key frame scheduling
     * and injection.
     */
    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(1);

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
     * A map that holds all the inject states.
     */
    private final Map<Long, InjectState> states = new HashMap<>();

    /**
     * Ctor.
     *
     * @param endpoint
     */
    public LipSyncHack(Endpoint endpoint)
    {
        this.endpoint = endpoint;
    }

    /**
     * Notifies this instance that an audio packet is about to be written.
     *
     * @param data
     * @param buffer
     * @param offset
     * @param length
     * @param source
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

        acceptedAudioSSRCs.add(acceptedAudioSSRC);

        // New audio stream. Trigger the hack for the associated video stream.
        List<RtpChannel> targetVideoChannels
            = endpoint.getChannels(MediaType.VIDEO);
        if (targetVideoChannels == null || targetVideoChannels.size() == 0)
        {
            return;
        }

        VideoChannel targetVC = (VideoChannel) targetVideoChannels.get(0);
        if (targetVC == null)
        {
            return;
        }

        List<RtpChannel> sourceVideoChannels
            = source.getEndpoint().getChannels(MediaType.VIDEO);
        if (sourceVideoChannels == null || sourceVideoChannels.size() == 0)
        {
            return;
        }

        VideoChannel sourceVC = (VideoChannel) sourceVideoChannels.get(0);
        if (sourceVC == null)
        {
            return;
        }

        // FIXME this will include rtx ssrcs and whatnot.
        for (int ssrc : sourceVC.getReceiveSSRCs())
        {
            Long receiveVideoSSRC = ssrc & 0xffffffffl;
            synchronized (states)
            {
                if (states.containsKey(receiveVideoSSRC))
                {
                    // This receive video SSRC has already been processed.
                    continue;
                }

                InjectState injectState = new InjectState(
                    receiveVideoSSRC, targetVC.getStream(), true);

                states.put(receiveVideoSSRC, injectState);

                InjectTask injectTask = new InjectTask(injectState);
                injectTask.schedule();
            }
        }
    }

    /**
     * Notifies this instance that a video packet is about to be written.
     */
    public void onRTPTranslatorWillWriteVideo(
        boolean accept, boolean data, byte[] buffer,
        int offset, int length, Channel target)
    {
        if (!accept || !data)
        {
            return;
        }

        Long acceptedVideoSSRC
            = RawPacket.getSSRCAsLong(buffer, offset, length);

        // In order to minimize the synchronization overhead, we process
        // only the first data packet of a given RTP stream.
        //
        // XXX No synchronization is required to r/w the acceptedVideoSSRCs
        // because in the current architecture this method is called by a single
        // thread at the time.
        if (acceptedVideoSSRCs.contains(acceptedVideoSSRC))
        {
            return;
        }

        acceptedVideoSSRCs.add(acceptedVideoSSRC);

        InjectState state;
        synchronized (states)
        {
            state = states.get(acceptedVideoSSRC);
            if (state == null)
            {
                // The hack has never been triggered for this stream.
                states.put(acceptedVideoSSRC, new InjectState(acceptedVideoSSRC,
                    ((RtpChannel) target).getStream(), false));

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
                return;
            }

            StreamRTPManager streamRTPManager
                = ((VideoChannel) target).getStream().getStreamRTPManager();

            ResumableStreamRewriter rewriter
                = streamRTPManager.ssrcToRewriter.get(acceptedVideoSSRC);

            if (rewriter == null)
            {
                int seqnum
                    = RawPacket.getSequenceNumber(buffer, offset, length);

                // Pretend we have dropped all the packets prior to the one
                // that's about to be written by the translator.
                int lastSeqnumDropped = RTPUtils.subtractNumber(seqnum, 1);
                int seqnumDelta = RTPUtils.subtractNumber(
                    lastSeqnumDropped, state.numOfKeyframesSent);

                long timestamp
                    = RawPacket.getTimestamp(buffer, offset, length);

                // Timestamps are calculated.
                long highestTimestampSent
                    = state.numOfKeyframesSent * TS_INCREMENT_PER_FRAME;

                // Pretend we have dropped all the packets prior to the one
                // that's about to be written by the translator.
                long lastTimestampDropped
                    = (timestamp - TS_INCREMENT_PER_FRAME) & 0xffffffffl;
                long timestampDelta
                    = (lastTimestampDropped - highestTimestampSent) & 0xffffffff;

                rewriter = new ResumableStreamRewriter(
                    state.numOfKeyframesSent, seqnumDelta,
                    highestTimestampSent, timestampDelta);

                streamRTPManager.ssrcToRewriter.put(acceptedVideoSSRC, rewriter);
            }
            else
            {
                logger.warn("Could not initialize a sequence number rewriter" +
                    "because one is already there.");
            }
        }

    }

    /**
     *
     */
    class InjectTask implements Runnable
    {
        /**
         * The state for this injector.
         */
        private final InjectState injectState;

        /**
         * The {@link ScheduledFuture} for this task.
         */
        private ScheduledFuture<?> scheduledFuture;

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
                    scheduledFuture.cancel(true);
                    return;
                }

                MediaStream mediaStream = injectState.target.get();
                if (mediaStream == null || !mediaStream.isStarted())
                {
                    logger.debug("Waiting for the media stream to become" +
                        "available.");
                    return;
                }

                try
                {
                    injectState.numOfKeyframesSent++;

                    // FIXME maybe grab from the write pool and copy the array?
                    byte[] buf = KEY_FRAME_BUFFER.clone();
                    RawPacket keyframe = new RawPacket(buf, 0, buf.length);

                    long timestamp
                        = injectState.numOfKeyframesSent * TS_INCREMENT_PER_FRAME;
                    keyframe.setSSRC(injectState.ssrc.intValue());
                    keyframe.setSequenceNumber(
                        injectState.numOfKeyframesSent);
                    keyframe.setTimestamp(timestamp);

                    logger.debug("Injecting black key frame ssrc=" + injectState.ssrc
                        + ", seqnum=" + injectState.numOfKeyframesSent
                        + ", timestamp=" + timestamp);

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
         *
         */
        public void schedule()
        {
            this.scheduledFuture = scheduler.scheduleAtFixedRate(
                this, WAIT_MS , KEY_FRAME_RATE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     *
     */
    static class InjectState
    {
        /**
         * The SSRC to send black key frames with.
         */
        private final Long ssrc;

        /**
         * The
         */
        private final WeakReference<MediaStream> target;

        /**
         *
         */
        private boolean active;

        /**
         * The number of key frames that have already been sent.
         */
        private int numOfKeyframesSent = 0;

        /**
         * Ctor.
         *
         * @param ssrc
         * @param target
         */
        public InjectState(Long ssrc, MediaStream target, boolean active)
        {
            this.ssrc = ssrc;
            this.active = active;
            this.target = new WeakReference<>(target);
        }
    }
}
