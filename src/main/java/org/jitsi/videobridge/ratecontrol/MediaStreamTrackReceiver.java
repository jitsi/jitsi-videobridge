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
package org.jitsi.videobridge.ratecontrol;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * @author George Politis
 */
public class MediaStreamTrackReceiver
    implements RTPEncodingResolver,
               TransformEngine
{
    /**
     * The {@link Logger} used by the {@link MediaStreamTrackReceiver} class to
     * print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(MediaStreamTrackReceiver.class);

    /**
     * The {@link RtpChannel} that this instance receives
     * {@link MediaStreamTrack}s from.
     */
    private final RtpChannel rtpChannel;

    /**
     * The signaled {@link MediaStreamTrack}s that this instance is receiving.
     */
    private MediaStreamTrackImpl[] tracks;

    /**
     * The {@link PacketTransformer} that transforms RTP packets for this
     * instance.
     */
    private RTPPacketTransformer rtpTransformer = new RTPPacketTransformer();

    /**
     * Ctor.
     *
     * @param rtpChannel The {@link RtpChannel} that this instance receives
     * {@link MediaStreamTrack}s from.
     */
    public MediaStreamTrackReceiver(RtpChannel rtpChannel)
    {
        this.rtpChannel = rtpChannel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RTPEncodingImpl resolveRTPEncoding(ByteArrayBuffer buf)
    {
        return resolve(buf);
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
        return null;
    }

    /**
     * Gets the first {@link RTPEncodingImpl} of the first
     * {@link MediaStreamTrackImpl} that has any encodings.
     *
     * @return the first {@link RTPEncodingImpl} of the first
     * {@link MediaStreamTrackImpl} that has any encodings, or null of there
     * are no encodings.
     */
    public RTPEncodingImpl getDefaultEncoding()
    {
        MediaStreamTrackImpl[] localTracks = tracks;
        if (localTracks == null || localTracks.length == 0)
        {
            return null;
        }

        // Usually we only have a single track with a bunch of encodings.
        RTPEncodingImpl[] firstTrackEncodings = tracks[0].getRTPEncodings();
        if (firstTrackEncodings != null && firstTrackEncodings.length != 0)
        {
            return firstTrackEncodings[0];
        }

        for (int i = 1; i < localTracks.length; i++)
        {
            RTPEncodingImpl[] encodings = localTracks[i].getRTPEncodings();
            if (encodings != null && encodings.length != 0)
            {
                return encodings[0];
            }
        }

        return null;
    }

    /**
     *
     * @param sources
     * @param sourceGroups
     */
    public boolean setRtpEncodingParameters(
        List<SourcePacketExtension> sources,
        List<SourceGroupPacketExtension> sourceGroups)
    {
        MediaStreamTrackImpl[] newTracks = MediaStreamTrackFactory
            .createMediaStreamTracks(this, sources, sourceGroups);

        boolean changed = false;
        synchronized (this)
        {
            if (tracks == null || tracks.length == 0)
            {
                tracks = newTracks;
                changed = true;
            }
            else
            {
                MediaStreamTrackImpl[] mergedTracks
                    = new MediaStreamTrackImpl[newTracks.length];

                for (int i = 0; i < newTracks.length; i++)
                {
                    MediaStreamTrackImpl newTrack = newTracks[i];

                    RTPEncodingImpl newEncoding = newTrack.getRTPEncodings()[0];
                    RTPEncodingImpl oldEncoding = resolve(newEncoding);
                    if (oldEncoding != null)
                    {
                        mergedTracks[i] = oldEncoding.getMediaStreamTrack();
                    }
                    else
                    {
                        mergedTracks[i] = newTrack;
                        changed = true;
                    }
                }

                tracks = mergedTracks;
            }
        }

        return changed;
    }

    /**
     * Gets all the {@link MediaStreamTrackImpl} signaled by the peer.
     * @return an array of all the {@link MediaStreamTrackImpl} signaled by the
     * peer.
     */
    public MediaStreamTrackImpl[] getMediaStreamTracks()
    {
        return tracks;
    }

    /**
     *
     * @param buf
     * @return
     */
    public RTPEncodingImpl resolve(ByteArrayBuffer buf)
    {
        if (buf == null)
        {
            return null;
        }

        return resolve(buf.getBuffer(), buf.getOffset(), buf.getLength());
    }

    /**
     *
     * @param buf
     * @param off
     * @param len
     * @return
     */
    public RTPEncodingImpl resolve(byte[] buf, int off, int len)
    {
        MediaStreamTrackImpl[] localTracks = tracks;
        if (localTracks == null || localTracks.length == 0)
        {
            return null;
        }

        for (MediaStreamTrackImpl track : localTracks)
        {
            for (RTPEncodingImpl encoding : track.getRTPEncodings())
            {
                if (encoding.matches(buf, off, len))
                {
                    return encoding;
                }
            }
        }

        return null;
    }

    /**
     *
     * @return
     */
    public RtpChannel getChannel()
    {
        return rtpChannel;
    }

    /**
     *
     * @param newEncoding
     * @return
     */
    private RTPEncodingImpl resolve(RTPEncodingImpl newEncoding)
    {
        if (newEncoding == null || tracks == null || tracks.length == 0)
        {
            return null;
        }

        for (MediaStreamTrackImpl mst : tracks)
        {
            if (newEncoding.equals(mst.getRTPEncodings()[0]))
            {
                return mst.getRTPEncodings()[0];
            }
        }

        return null;
    }

    /**
     *
     */
    private class RTPPacketTransformer
        extends SinglePacketTransformerAdapter
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            RTPEncodingImpl encoding = resolve(pkt);
            if (encoding == null)
            {
                return pkt;
            }

            long nowMs = System.currentTimeMillis();

            // Update rate statistics.
            encoding.getRateStatistics().update(pkt.getLength(), nowMs);

            // When we see a new frame, calculate the bitrate and if the track
            // is multistream maybe set active status.
            long ts = pkt.getTimestamp();
            long maxTs = encoding.getMaxTimestamp();
            if (maxTs != -1 && TimeUtils.rtpDiff(maxTs, ts) >= 0)
            {
                return pkt;
            }

            if (!rtpChannel.getStream().isStartOfFrame(
                pkt.getBuffer(), pkt.getOffset(), pkt.getLength()))
            {
                return pkt;
            }

            encoding.setMaxTimestamp(ts);

            encoding.setLastStableBitrateBps(encoding.getBitrateBps(nowMs));

            MediaStreamTrackImpl track = encoding.getMediaStreamTrack();
            if (!track.isMultiStream())
            {
                return pkt;
            }

            // Detect simulcast stream resume event.
            long deltaMs = nowMs - track.getMillisSinceLastKeyframe();
            if (deltaMs > 300 && rtpChannel.getStream().isKeyFrame(
                pkt.getBuffer(), pkt.getOffset(), pkt.getLength()))
            {
                track.setMillisSinceLastKeyframe(nowMs);

                boolean isActive = false;
                // RTP engines may decide to suspend a stream for congestion
                // control. This is the case with the webrtc.org simulcast
                // implementation. There is a streaming dependency between the
                // encodings of the track, if
                // implicit bitrate ordering because engines decide to suspend
                // stream for congestion control
                // this assumes that and that the array is ordered in a way to
                // represent this.
                RTPEncodingImpl[] encodings = track.getRTPEncodings();
                for (int i = encodings.length - 1; i >= 0; i--)
                {
                    if (!isActive && encodings[i].requires(encoding))
                    {
                        isActive = true;
                    }

                    if (encodings[i].isActive() != isActive)
                    {
                        encodings[i].setActive(isActive);
                    }
                }
            }

            return pkt;
        }
    }
}
