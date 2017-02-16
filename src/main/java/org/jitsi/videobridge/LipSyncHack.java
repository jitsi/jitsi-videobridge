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
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
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
    implements TransformEngine
{
    /**
     * A byte array holding a black VP8 key frame. The byte array contains the
     * full RTP packet and not just the VP8 payload (160 bytes).
     */
    public static final byte[] KEY_FRAME_BUFFER = new byte[]{
        (byte) 0x90, (byte) 0xe4, (byte) 0x38, (byte) 0x25,
        (byte) 0x53, (byte) 0x50, (byte) 0x6c, (byte) 0x8b,
        (byte) 0x8e, (byte) 0x71, (byte) 0xf1, (byte) 0x72,
        (byte) 0xbe, (byte) 0xde, (byte) 0x00, (byte) 0x01,
        (byte) 0x32, (byte) 0x62, (byte) 0x45, (byte) 0xaa,
        (byte) 0x90, (byte) 0xe0, (byte) 0xd6, (byte) 0xf0,
        (byte) 0x70, (byte) 0x20, (byte) 0x10, (byte) 0x0f,
        (byte) 0x00, (byte) 0x9d, (byte) 0x01, (byte) 0x2a,
        (byte) 0x40, (byte) 0x01, (byte) 0xb4, (byte) 0x00,
        (byte) 0x07, (byte) 0x07, (byte) 0x09, (byte) 0x03,
        (byte) 0x0b, (byte) 0x0b, (byte) 0x11, (byte) 0x33,
        (byte) 0x09, (byte) 0x10, (byte) 0x4b, (byte) 0x00,
        (byte) 0x00, (byte) 0x0c, (byte) 0x2c, (byte) 0x09,
        (byte) 0xee, (byte) 0x0d, (byte) 0x02, (byte) 0xc9,
        (byte) 0x3e, (byte) 0xd7, (byte) 0xb7, (byte) 0x36,
        (byte) 0x4e, (byte) 0x70, (byte) 0xf6, (byte) 0x4e,
        (byte) 0x70, (byte) 0xf6, (byte) 0x4e, (byte) 0x70,
        (byte) 0xf6, (byte) 0x4e, (byte) 0x70, (byte) 0xf6,
        (byte) 0x4e, (byte) 0x70, (byte) 0xf6, (byte) 0x4e,
        (byte) 0x70, (byte) 0xf6, (byte) 0x4e, (byte) 0x70,
        (byte) 0xf6, (byte) 0x4e, (byte) 0x70, (byte) 0xf6,
        (byte) 0x4e, (byte) 0x70, (byte) 0xf6, (byte) 0x4e,
        (byte) 0x70, (byte) 0xf6, (byte) 0x4e, (byte) 0x70,
        (byte) 0xf6, (byte) 0x4e, (byte) 0x70, (byte) 0xf6,
        (byte) 0x4e, (byte) 0x70, (byte) 0xf6, (byte) 0x4e,
        (byte) 0x70, (byte) 0xf6, (byte) 0x4e, (byte) 0x70,
        (byte) 0xf6, (byte) 0x4e, (byte) 0x70, (byte) 0xf6,
        (byte) 0x4e, (byte) 0x70, (byte) 0xf6, (byte) 0x4e,
        (byte) 0x70, (byte) 0xf6, (byte) 0x4e, (byte) 0x70,
        (byte) 0xf6, (byte) 0x4e, (byte) 0x70, (byte) 0xf6,
        (byte) 0x4e, (byte) 0x70, (byte) 0xf6, (byte) 0x4e,
        (byte) 0x70, (byte) 0xf6, (byte) 0x4e, (byte) 0x70,
        (byte) 0xf6, (byte) 0x4e, (byte) 0x70, (byte) 0xf6,
        (byte) 0x4e, (byte) 0x70, (byte) 0xf6, (byte) 0x4e,
        (byte) 0x70, (byte) 0xf6, (byte) 0x4e, (byte) 0x70,
        (byte) 0xf6, (byte) 0x4e, (byte) 0x70, (byte) 0xf6,
        (byte) 0x4e, (byte) 0x70, (byte) 0xf6, (byte) 0x4e,
        (byte) 0x70, (byte) 0xf6, (byte) 0x4e, (byte) 0x70,
        (byte) 0xf6, (byte) 0x4e, (byte) 0x5c, (byte) 0x00,
        (byte) 0xfe, (byte) 0xef, (byte) 0xb9, (byte) 0x00
    };

    /**
     * Configuration property for  how many black key frames to send during an
     * injection period.
     */
    public final static String KEY_FRAMES_PER_INJECTION_PNAME
        = "org.jitsi.videobridge.LipSyncHack.KEY_FRAMES_PER_INJECTION_PNAME";

    /**
     * Configuration property for the black key frames send period (in ms).
     */
    public final static String PERIOD_MS_PNAME
        = "org.jitsi.videobridge.LipSyncHack.PERIOD_MS_PNAME";

    /**
     * The <tt>ConfigurationService</tt> used to load LS hack configuration.
     */
    private final static ConfigurationService cfg
        = LibJitsi.getConfigurationService();

    /**
     * A constant defining how many black key frames to send during an injection
     * period.
     */
    private static final int KEY_FRAMES_PER_PERIOD
        = cfg.getInt(KEY_FRAMES_PER_INJECTION_PNAME, 10);

    /**
     *  Black key frames send period (in ms).
     */
    private static final int PERIOD_MS = cfg.getInt(PERIOD_MS_PNAME, 500);

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
     * The <tt>RecurringRunnableExecutor</tt> to be utilized by the
     * <tt>LipSyncHack</tt> class and its instances. It drives the injectors.
     */
    private final RecurringRunnableExecutor
        recurringRunnableExecutor = new RecurringRunnableExecutor(
        LipSyncHack.class.getSimpleName());

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
     * Listens for RTCP report blocks and stops the injection if we receive an
     * RTCP report for an SSRC that we're hacking.
     */
    private RTCPReportListener rtcpReportListener;

    /**
     * The owner (and, consequently, the destination) of this hack.
     */
    private final VideoChannel dest;

    /**
     * The remote audio SSRCs that have been accepted by the translator and
     * forwarded to the endpoint associated to this instance. Accessed by the
     * audio translator thread only (no sync needed).
     */
    private final List<Long> acceptedAudioSSRCs = new ArrayList<>();

    /**
     * The remote video SSRCs that have been accepted by the translator and
     * forwarded to the endpoint associated to this instance. Accessed by the
     * video translator thread only (no sync needed).
     */
    private final List<Long> acceptedVideoSSRCs = new ArrayList<>();

    /**
     * The sync root to synchronize the A/V translator write threads.
     */
    private final Object filterSyncRoot = new Object();

    /**
     * The {@link Map} of SSRCs for which we've sent out black VP8 key frames.
     */
    private final Map<Long, Injection> injections = new HashMap<>();

    /**
     * The {@link Map} of transformations necessary per video SSRCs.
     */
    private final Map<Long, Transformation> transformations
        = new ConcurrentHashMap<>();

    /**
     * Ctor.
     *
     * @param dest the {@link VideoChannel} that owns this hack.
     */
    LipSyncHack(VideoChannel dest)
    {
        this.dest = dest;
    }

    /**
     * Notifies this instance that an audio packet (RTP or RTCP) is about to be
     * written. The purpose of this is to trigger the hack for the video stream
     * that is associated to the audio SSRC that is about to be written.
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
        byte[] buffer, int offset, int length, RtpChannel source)
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
        if (dest == null || (stream = dest.getStream()) == null
            || !stream.isStarted())
        {
            // It seems like we're not ready yet to trigger the hack.
            return;
        }

        MediaStreamTrackDesc[] sourceTracks
            = source.getEndpoint().getMediaStreamTracks(MediaType.VIDEO);
        if (ArrayUtils.isNullOrEmpty(sourceTracks))
        {
            // It seems like we're not ready yet to trigger the hack.
            return;
        }

        RTPEncodingDesc[] sourceEncodings = sourceTracks[0].getRTPEncodings();
        if (ArrayUtils.isNullOrEmpty(sourceEncodings))
        {
            // It seems like we're not ready yet to trigger the hack.
            return;
        }

        long receiveVideoSSRC = sourceEncodings[0].getPrimarySSRC();
        if (receiveVideoSSRC < 0)
        {
            // It seems like we're not ready yet to trigger the hack.
            return;
        }

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
                // No need to hack this SSRC, as it's either already accepted
                // (in other words, media has started flowing for this SSRC) or
                // we're already injecting (since we have an injection).
                //
                // In either case, we don't want to start another injection
                // task.
                return;
            }

            Injection injection = new Injection(receiveVideoSSRC);
            injections.put(receiveVideoSSRC, injection);
            recurringRunnableExecutor.registerRecurringRunnable(injection);

            if (rtcpReportListener == null)
            {
                // One way to stop the injection is to receive an RTCP report
                // for the SSRC that we're hacking.
                rtcpReportListener = new RTCPReportListener();
                stream.getMediaStreamStats()
                    .getRTCPReports().addRTCPReportListener(rtcpReportListener);
            }
        }
    }

    /**
     * Makes {@code sz} black key frame packets within the range specified by
     * {@code seqNumOff}.
     *
     * @param ssrc the SSRC of the black key frame packets.
     * @param seqNumOff the sequence number offset of the black key frame
     * packets.
     * @param tsOff the RTP timestamp offset of the black key frame packets.
     * @param sz the number of black key frame packets to create.
     * @return the array of black key frame packets that were created.
     */
    private static RawPacket[] make(int ssrc, int seqNumOff, long tsOff, int sz)
    {
        RawPacket[] kfs = new RawPacket[sz];
        for (int i = 0; i < sz; i++)
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
            long timestamp = (tsOff + i * TS_INCREMENT_PER_FRAME) & 0xFFFFFFFFL;
            kfs[i].setTimestamp(timestamp);
        }

        return kfs;
    }

    /**
     * Notifies this instance that a video packet (RTP or RTCP) is about to be
     * written. The purpose of this is to stop the hack for the SSRC that is
     * about to be written.
     *
     * @param buffer the buffer which contains the bytes of the received RTP or
     * RTCP packet.
     * @param offset the zero-based index in <tt>buffer</tt> at which the bytes
     * of the received RTP or RTCP packet begin.
     * @param length the number of bytes in <tt>buffer</tt> beginning at
     * <tt>offset</tt> which represent the received RTP or RTCP packet.
     * @param source the {@link RtpChannel} where this packet came from.
     */
    void onRTPTranslatorWillWriteVideo(
        byte[] buffer, int offset, int length, RtpChannel source)
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

            // Make sure we mark as accepted all simulcast SSRCs.
            MediaStreamTrackDesc[] sourceTracks = source.getStream()
                .getMediaStreamTrackReceiver().getMediaStreamTracks();

            if (!ArrayUtils.isNullOrEmpty(sourceTracks))
            {
                RTPEncodingDesc[] sourceEncodings
                    = sourceTracks[0].getRTPEncodings();
                if (!ArrayUtils.isNullOrEmpty(sourceEncodings))
                {
                    // Override the accepted SSRC to be the SSRC of the first
                    // encoding, so that we can find the injection in the map.
                    acceptedVideoSSRC = sourceEncodings[0].getPrimarySSRC();
                    for (RTPEncodingDesc sourceEncoding : sourceEncodings)
                    {
                        long primarySSRC = sourceEncoding.getPrimarySSRC();
                        if (primarySSRC > -1)
                        {
                            acceptedVideoSSRCs.add(primarySSRC);
                        }
                    }
                }
            }

            Injection injectState = injections.get(acceptedVideoSSRC);
            if (injectState == null)
            {
                // We haven't injected anything, our job here is done.
                return;
            }

            // We're getting media => stop the injection and add a translation
            // for the outgoing media packets.

            injectState.stop();
            recurringRunnableExecutor.deRegisterRecurringRunnable(injectState);

            int seqNum = RawPacket.getSequenceNumber(buffer, offset, length);
            long ts = RawPacket.getTimestamp(buffer, offset, length);

            // NOTE we reserve 10 slots for late media packets.
            int seqNumDelta = (injectState.maxSeqNum + 10 - seqNum) & 0xFFFF;
            long tsDelta = (injectState.maxTs
                + 10 * TS_INCREMENT_PER_FRAME - ts) & 0xFFFFFFFFL;

            if (logger.isDebugEnabled())
            {
                logger.debug("new_translation" +
                    ",stream=" + dest.getStream().hashCode() +
                    " ssrc=" + acceptedVideoSSRC +
                    ",ts_delta=" + tsDelta +
                    ",seq_num_delta=" + seqNumDelta);
            }

            // Setup packet transformation.
            transformations.put(
                acceptedVideoSSRC, new Transformation(tsDelta, seqNumDelta));
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
            recurringRunnableExecutor.close();

            if (rtcpReportListener != null)
            {
                dest.getStream().getMediaStreamStats().getRTCPReports()
                    .removeRTCPReportListener(rtcpReportListener);
            }
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
                    // if we have an injection, then that means that at this
                    // point we MUST have a transformation (because before
                    // reaching this point, we MUST have accepted the ssrc, and
                    // that needs to have stopped the injection and added a
                    // transformation).
                    assert !injections.containsKey(ssrc);

                    // Mark that this RTP stream has been prepended so that we
                    // don't run this block again.
                    state = new Transformation(0, 0);

                    // Prepend.
                    int seqNumOff = (pkts[i].getSequenceNumber() - 10) & 0xFFFF;
                    long tsOff = (pkts[i].getTimestamp()
                        - 10 * TS_INCREMENT_PER_FRAME) & 0xFFFFFFFFL;
                    RawPacket[] extras = make(
                        ssrc.intValue(), seqNumOff, tsOff, 10);

                    cumulExtras = ArrayUtils.concat(cumulExtras, extras);

                    if (logger.isDebugEnabled())
                    {
                        logger.debug("new_translation" +
                            ",stream=" + dest.getStream().hashCode() +
                            " ssrc=" + ssrc +
                            ",ts_delta=0,seq_num_delta=0");
                    }

                    transformations.put(ssrc, state);
                }

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

                if (logger.isDebugEnabled())
                {
                    logger.debug("ls_rewrite src_ssrc=" + pkts[i].getSSRCAsLong()
                        + ",src_seq=" + srcSeqNum
                        + ",src_ts=" + srcTs
                        + ",dst_ssrc=" + pkts[i].getSSRCAsLong()
                        + ",dst_seq=" + dstSeqNum
                        + ",dst_ts=" + dstTs);
                }
            }

            return ArrayUtils.concat(cumulExtras, pkts);
        }
    }

    /**
     *
     */
    private class RTCPTransformer
        extends SinglePacketTransformerAdapter
    {
        /**
         * Ctor.
         */
        RTCPTransformer()
        {
            super(RTCPPacketPredicate.INSTANCE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket transform(RawPacket pkt)
        {
            if (injections.isEmpty())
            {
                return pkt;
            }

            RTCPIterator it = new RTCPIterator(pkt);
            while (it.hasNext())
            {
                ByteArrayBuffer baf = it.next();
                switch (RTCPHeaderUtils.getPacketType(baf))
                {
                case RTCPPacket.SR:
                    long ssrc = RawPacket.getRTCPSSRC(baf);
                    Transformation state = transformations.get(ssrc);
                    if (state != null)
                    {
                        // Rewrite timestamp.
                        long srcTs = RTCPSenderInfoUtils.getTimestamp(baf);
                        long dstTs = state.rewriteTimestamp(srcTs);

                        if (srcTs != dstTs)
                        {
                            RTCPSenderInfoUtils.
                                setTimestamp(baf, (int) dstTs);
                        }
                    }
                }
            }

            return pkt;
        }
    }

    /**
     *
     */
    private class Injection
        implements RecurringRunnable
    {
        /**
         * Ctor.
         *
         * @param receiveVideoSSRC
         */
        Injection(long receiveVideoSSRC)
        {
            this.receiveVideoSSRC = receiveVideoSSRC;
        }

        /**
         *
         */
        final long receiveVideoSSRC;

        /**
         * The maximum sequence number (mod 2^16) that has been sent out by
         * this injector instance.
         */
        int maxSeqNum = -1;

        /**
         * The maximum RTP timstamp (mod 2^32) that has been sent out by
         * this injector instance.
         */
        long maxTs = -1;

        /**
         * A boolean indicating whether or not this instance is still injecting
         * packets.
         */
        boolean running = true;

        /**
         * The last time in milliseconds at which {@link #run} was invoked.
         */
        long lastRunMs = -1;

        /**
         *
         */
        @Override
        public long getTimeUntilNextRun()
        {
            if (!running)
            {
                return Long.MAX_VALUE;
            }

            long timeSinceLastProcess
                = Math.max(System.currentTimeMillis() - lastRunMs, 0);

            return Math.max(PERIOD_MS - timeSinceLastProcess, 0);
        }

        /**
         *
         */
        @Override
        public void run()
        {
            if (!running)
            {
                return;
            }

            lastRunMs = System.currentTimeMillis();

            MediaStream stream = dest.getStream();
            if (stream == null)
            {
                return;
            }

            // This is synchronized with the stop method (which updates the
            // running field). The stop method is invoked when the video
            // translator thread receives a video RTP packet and right before
            // the maxSeqNum and maxTs fields are accessed by the video
            // translator write thread.

            // The idea here is to serialize r/w of maxSeqNum and maxTs, to make
            // sure the video translator thread is not using an outdated
            // maxSeqNum value.
            synchronized (this)
            {
                if (!running)
                {
                    return;
                }

                if (maxSeqNum == -1)
                {
                    // Init (pretend we've sent maxSeqNum and maxTs).
                    maxSeqNum = rnd.nextInt(0xFFFF);
                    maxTs = rnd.nextInt() & 0xFFFFFFFFL;
                }
                else
                {
                    // Pre-allocate KEY_FRAMES_PER_PERIOD spots and exit the
                    // synchronize block to minimize contention.
                    maxSeqNum = (maxSeqNum + KEY_FRAMES_PER_PERIOD) & 0xFFFF;
                    maxTs = (maxTs
                        + KEY_FRAMES_PER_PERIOD * TS_INCREMENT_PER_FRAME)
                            & 0xFFFFFFFFL;
                }
            }

            long tsOff
                = (maxTs - (KEY_FRAMES_PER_PERIOD - 1) * TS_INCREMENT_PER_FRAME)
                    & 0xFFFFFFFFL;

            int seqNumOff = (maxSeqNum - KEY_FRAMES_PER_PERIOD - 1) & 0xFFFF;
            RawPacket[] kfs = make(
                (int) receiveVideoSSRC, seqNumOff, tsOff, KEY_FRAMES_PER_PERIOD);

            for (int i = 0; i < KEY_FRAMES_PER_PERIOD; i++)
            {
                try
                {
                    stream.injectPacket(kfs[i], true, LipSyncHack.this);
                }
                catch (TransmissionFailedException e)
                {
                    logger.error("failed to inject a black keyframe.", e);
                }
            }

            if (logger.isDebugEnabled())
            {
                logger.debug("new_injection" +
                    ",stream=" + stream.hashCode() +
                    " ssrc=" + receiveVideoSSRC +
                    ",max_ts=" + kfs[kfs.length - 1].getTimestamp() +
                    ",max_seq_num=" + kfs[kfs.length - 1].getSequenceNumber());
            }
        }

        /**
         * After stop is called, the injection logic is stopped and maxSeqNum
         * and maxTs will not be further modified.
         */
        public synchronized void stop()
        {
            running = false;
        }
    }

    class RTCPReportListener
        extends RTCPReportAdapter
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public void rtcpReportReceived(RTCPReport report)
        {
            for (RTCPFeedback feedback : report.getFeedbackReports())
            {
                long ssrc = feedback.getSSRC();
                Injection injection = injections.get(ssrc);
                if (injection != null
                    && !transformations.containsKey(ssrc))
                {
                    // NOTE if we have a transformation that means that we're no
                    // longer injecting that SSRC.
                    injection.stop();
                }
            }
        }
    }
}
