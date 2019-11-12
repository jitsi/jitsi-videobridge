package org.jitsi.videobridge.cc.vp8;

public interface VP8ProjectionRecord
{
    int getEarliestProjectedSequence();
    int getLatestProjectedSequence();

    long getTimestamp();
}
