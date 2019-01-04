package org.jitsi.videobridge.sctp;

import org.jitsi.nlj.PacketInfo;
import org.jitsi_modified.sctp4j.Sctp4j;
import org.jitsi_modified.sctp4j.SctpDataCallback;
import org.jitsi_modified.sctp4j.SctpDataSender;
import org.jitsi_modified.sctp4j.SctpSocket;

import java.nio.ByteBuffer;

/**
 * Manages the SCTP connection and handles incoming and outgoing SCTP packets.
 *
 * We create one of these per endpoint and currently only support a single SCTP connection per endpoint, because we
 * don't (currently) determine which specific SCTP socket to which an incoming SCTP packet belongs.  (I'm assuming
 * there is a way to do this by inspecting the received packet, but we don't currently have a use case for that
 * anyway).
 */
public class SctpManager {
    private SctpSocket socket = null;
    /**
     * The {@link SctpDataSender} is necessary from the start, as it's needed to send outgoing SCTP protocol packets
     * (e.g. used in the connection negotiation)
     */
    private final SctpDataSender dataSender;
    /**
     * The {@link SctpDataCallback} can be set at any time, and will be invoked with any SCTP app packets (that is,
     * application packets which have been sent over SCTP, e.g. datachannel packets).
     */
    private SctpDataCallback dataCallback;
    static {
        Sctp4j.init();
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
        socket.onConnIn(packetBuffer.array(), packetBuffer.arrayOffset(), packetBuffer.limit());
    }

    public void onSctpAppData(SctpDataCallback dataCallback) {
        this.dataCallback = dataCallback;
    }

    public void createConnection() {
        socket = Sctp4j.createSocket();
        socket.outgoingDataSender = this.dataSender;
        socket.dataCallback = (data, sid, ssn, tsn, ppid, context, flags) -> {
            if (this.dataCallback != null) {
                this.dataCallback.onSctpPacket(data, sid, ssn, tsn, ppid, context, flags);
            } else {
                // TODO: my thought is that we shouldn't get any app data until we start a datachannel negotiation,
                // which should have installed itself as a datacallback, so throwing for now to verify if that's the
                // case
                throw new Error("Received SCTP app data but no handler!");
            }
        };
        socket.eventHandler = new SctpSocket.SctpSocketEventHandler() {
            @Override
            public void onConnected() {
                System.out.println("SCTP connected!");
            }

            @Override
            public void onDisconnected() {
                System.out.println("SCTP disconnected!");
            }
        };
        socket.listen();
    }

    public void closeConnection() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }
}
