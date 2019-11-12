package org.jitsi.videobridge.cc.vp8;

/**
 * A record of a VP8 frame that was received but not projected.
 * This is used to remember the mapping of the frame in the sequence number
 * timeline, so that other frames' sequence numbers can be assigned properly.
 */
public class VP8UnprojectedFrame implements VP8ProjectionRecord
{
    final int sequencePoint;
    final long timestamp;

    public VP8UnprojectedFrame(int sequencePoint, long timestamp)
    {
        this.sequencePoint = sequencePoint;
        this.timestamp = timestamp;
    }

    @Override
    public int getEarliestProjectedSequence()
    {
        return sequencePoint;
    }

    @Override
    public int getLatestProjectedSequence()
    {
        return sequencePoint;
    }

    @Override
    public long getTimestamp()
    {
        return timestamp;
    }
}
