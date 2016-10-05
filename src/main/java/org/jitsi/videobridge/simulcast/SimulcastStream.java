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

import org.jitsi.impl.neomedia.*;

/**
 * The <tt>SimulcastStream</tt> of a <tt>SimulcastReceiver</tt> represents a
 * simulcast stream. It determines when a simulcast stream has been
 * stopped/started and fires a property change event when that happens. It also
 * gathers bitrate statistics for the associated stream.
 *
 * @author George Politis
 * @author Lyubomir Marinov
 */
public class SimulcastStream
    implements Comparable<SimulcastStream>
{
    /**
     * Base simlucast stream quality order.
     */
    public static final int SIMULCAST_LAYER_ORDER_BASE = 0;

    /**
     * The <tt>SimulcastReceiver</tt> that owns this simulcast stream.
     */
    private final SimulcastReceiver simulcastReceiver;

    /**
     * The primary SSRC for this simulcast stream.
     */
    private final long primarySSRC;

    /**
     * The RTX SSRC for this simulcast stream.
     */
    private final long rtxSSRC;

    /**
     * The FEC SSRC for this simulcast stream.
     *
     * XXX This isn't currently used anywhere because Chrome doens't use a
     * separate SSRC for FEC.
     */
    private final long fecSSRC;

    /**
     * The order of this simulcast stream.
     */
    private final int order;

    /**
     * Holds a boolean indicating whether or not this simulcast stream is
     * streaming.
     */
    boolean isStreaming = false;

    /**
     * The value of the RTP marker (bit) of the last {@code RawPacket} seen by
     * this {@code SimulcastStream}. Technically, the order of the receipt of RTP
     * packets may be disturbed by the network transport (e.g. UDP) and/or RTP
     * packet retransmissions so the value of {@code lastPktMarker} may not come
     * from the last received {@code RawPacket} but from a received
     * {@code RawPacket} which would have been the last received if there were
     * no network transport and RTP packet retransmission aberrations.
     */
    Boolean lastPktMarker;

    /**
     * The highest RTP {@code sequenceNumber} received by this {@code
     * SimulcastStream}. The order of the receipt of RTP packets may be
     * disturbed by the network transport (e.g. UDP) and/or RTP packet
     * retransmissions so the value of {@code lastPktSequenceNumber} may
     * not come from the last received {@code RawPacket} but from a received
     * {@code RawPacket} which would have been the last received if there were
     * no network transport and RTP packet retransmission aberrations.
     */
    int lastPktSequenceNumber = -1;

    /**
     * The {@code timestamp} of the last {@code RawPacket} seen by this
     * {@code SimulcastStream}. Technically, the order of the receipt of RTP
     * packets may be disturbed by the network transport (e.g. UDP) and/or RTP
     * packet retransmissions so the value of {@code lastPktTimestamp} may not
     * come from the last received {@code RawPacket} but from a received
     * {@code RawPacket} which would have been the last received if there were
     * no network transport and RTP packet retransmission aberrations.
     */
    long lastPktTimestamp = -1;

    /**
     * Ctor.
     *
     * @param simulcastReceiver
     * @param primarySSRC
     * @param rtxSSRC
     * @param fecSSRC
     * @param order
     */
    public SimulcastStream(
            SimulcastReceiver simulcastReceiver,
            long primarySSRC,
            long rtxSSRC,
            long fecSSRC,
            int order)
    {
        this.simulcastReceiver = simulcastReceiver;
        this.primarySSRC = primarySSRC;
        this.rtxSSRC = rtxSSRC;
        this.fecSSRC = fecSSRC;
        this.order = order;
    }

    /**
     * Gets the primary SSRC for this simulcast stream.
     *
     * @return the primary SSRC for this simulcast stream.
     */
    public long getPrimarySSRC()
    {
        return primarySSRC;
    }

    /**
     * Gets the RTX SSRC for this simulcast stream.
     *
     * @return the RTX SSRC for this simulcast stream.
     */
    public long getRTXSSRC()
    {
        return rtxSSRC;
    }

    /**
     * Gets the FEC SSRC for this simulcast stream.
     *
     * @return the FEC SSRC for this simulcast stream.
     */
    public long getFECSSRC()
    {
        return fecSSRC;
    }

    /**
     * Gets the order of this simulcast stream.
     *
     * @return the order of this simulcast stream.
     */
    public int getOrder()
    {
        return order;
    }

    /**
     * Determines whether a packet belongs to this {@code SimulcastStream}.
     *
     * @param pkt
     * @return {@code true} if {@code pkt} belongs to this
     * {@code SimulcastStream}; {@code false}, otherwise.
     */
    public boolean matches(RawPacket pkt)
    {
        if (pkt == null)
        {
            return false;
        }

        long ssrc = pkt.getSSRCAsLong();
        return ssrc == primarySSRC || ssrc == rtxSSRC || ssrc == fecSSRC;
    }

    /**
     * Compares this simulcast stream with another, implementing an order.
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(SimulcastStream o)
    {
        return (o == null) ? 1 : (order - o.order);
    }

    /**
     *
     * Gets a boolean indicating whether or not this simulcast stream is
     * streaming.
     *
     * @return true if this simulcast stream is streaming, false otherwise.
     */
    public boolean isStreaming()
    {
        // NOTE(gp) we assume 1. that the base stream is always streaming, and
        // 2. if stream N is streaming, then stream N-1 is streaming. N == order
        // in this class TAG(simulcast-assumption,arbitrary-sim-simStreams).
        return isStreaming || order == 0;
    }

    /**
     * Utility method that asks for a keyframe for a specific simulcast stream.
     * This is typically done when switching streams. This method is executed in
     * the same thread that processes incoming packets. We must not block packet
     * reading so we request the key frame on a newly spawned thread.
     */
    public void askForKeyframe()
    {
        simulcastReceiver.askForKeyframe(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        SimulcastStream that = (SimulcastStream) o;

        if (primarySSRC != that.primarySSRC)
        {
            return false;
        }
        if (rtxSSRC != that.rtxSSRC)
        {
            return false;
        }
        if (fecSSRC != that.fecSSRC)
        {
            return false;
        }
        return order == that.order;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode()
    {
        int result = (int) (primarySSRC ^ (primarySSRC >>> 32));
        result = 31 * result + (int) (rtxSSRC ^ (rtxSSRC >>> 32));
        result = 31 * result + (int) (fecSSRC ^ (fecSSRC >>> 32));
        result = 31 * result + order;
        return result;
    }
}
