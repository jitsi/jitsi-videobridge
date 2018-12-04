package org.jitsi.videobridge;

import org.jetbrains.annotations.NotNull;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.transform.node.Node;

import java.util.List;

/**
 * A node which bridges the input transform chain packet flow with the SCTP connection
 */
public class SctpConnectionIngressWrapperNode extends Node {
    private final SctpConnection2 sctpConnection;
    public SctpConnectionIngressWrapperNode(String id, SctpConnection2 sctpConnection) {
        super("SCTP connection ingress " + id);
        this.sctpConnection = sctpConnection;
    }

    @Override
    protected void doProcessPackets(@NotNull List<PacketInfo> packetInfos) {
        packetInfos.forEach(sctpConnection::enqueuePacket);
    }
}
