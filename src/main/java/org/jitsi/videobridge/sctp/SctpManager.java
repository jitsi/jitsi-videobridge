package org.jitsi.videobridge.sctp;

import org.jitsi.nlj.*;
import org.jitsi_modified.sctp4j.*;

import java.nio.ByteBuffer;

/**
 * Manages the SCTP connection and handles incoming and outgoing SCTP packets.
 *
 * We create one of these per endpoint and currently only support a single SCTP connection per endpoint, because we
 * that's all we use.
 */
public class SctpManager {
    private SctpSocket socket = null;
    /**
     * The {@link SctpDataSender} is necessary from the start, as it's needed to send outgoing SCTP protocol packets
     * (e.g. used in the connection negotiation)
     */
    private final SctpDataSender dataSender;

    // We hard-code 5000 in the offer, so just mark it as the default here.
    private static int DEFAULT_SCTP_PORT = 5000;
    static {
        Sctp4j.init(DEFAULT_SCTP_PORT);
    }

    public SctpManager(SctpDataSender dataSender) {
        this.dataSender = dataSender;
    }

    /**
     * Process an incoming SCTP packet from the network
     * @param sctpPacket
     */
    public void handleIncomingSctp(PacketInfo sctpPacket) {
        ByteBuffer packetBuffer = sctpPacket.getPacket().getBuffer();
        System.out.println("SCTP socket " + socket.hashCode() + " received a packet of size " + packetBuffer.limit());
        socket.onConnIn(packetBuffer.array(), packetBuffer.arrayOffset(), packetBuffer.limit());
    }

    public SctpServerSocket createServerSocket()
    {
        socket = Sctp4j.createServerSocket(DEFAULT_SCTP_PORT);
        socket.outgoingDataSender = this.dataSender;
        return (SctpServerSocket)socket;
    }

    public SctpClientSocket crerateClientSocket() {
        socket = Sctp4j.createClientSocket(DEFAULT_SCTP_PORT);
        socket.outgoingDataSender = this.dataSender;
        return (SctpClientSocket)socket;
    }

    public void closeConnection() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }
}
