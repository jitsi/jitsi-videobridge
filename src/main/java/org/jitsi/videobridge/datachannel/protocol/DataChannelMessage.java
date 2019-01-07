package org.jitsi.videobridge.datachannel.protocol;

import java.nio.*;

public abstract class DataChannelMessage
{
    public abstract ByteBuffer getBuffer();
}
