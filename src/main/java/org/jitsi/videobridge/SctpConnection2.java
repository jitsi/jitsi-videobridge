package org.jitsi.videobridge;

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.util.PacketExtensionsKt;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.UnparsedPacket;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.util.Logger;
import org.jitsi_modified.sctp4j.Sctp;
import org.jitsi_modified.sctp4j.SctpDataCallback;
import org.jitsi_modified.sctp4j.SctpSocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SctpConnection2
    implements SctpDataCallback {
    private static final Logger logger
            = Logger.getLogger(SctpConnection2.class);
    private String id;
    //TODO: this will come from elsewhere
    private LinkedBlockingQueue<PacketInfo> incomingSctpPackets = new LinkedBlockingQueue<>();
    private LinkedBlockingQueue<PacketInfo> outgoingSctpPackets = new LinkedBlockingQueue<>();
    private DataChannelManager dataChannelManager;
    private AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private SctpSocket sctpSocket;

    public SctpConnection2(String id, ExecutorService executor) {
        this.id = id;
        this.executor = executor;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            //TODO: why not just do this on bridge startup?
            Sctp.init();
            sctpSocket = Sctp.createSocket(5000);
            sctpSocket.setLink((sctpSocket, bytes) -> {
                Packet data = new UnparsedPacket(ByteBuffer.wrap(bytes));
                //TODO: add an event to the timeline here?
                outgoingSctpPackets.add(new PacketInfo(data));
            });
            this.dataChannelManager = new DataChannelManager(id, sctpSocket);
            executor.execute(this::doWork);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            sctpSocket.close();
            sctpSocket = null;
        }
    }

    public void enqueuePacket(PacketInfo packetInfo) {
        incomingSctpPackets.add(packetInfo);
    }

    private void doWork() {
        while (running.get()) {
            try {
                PacketInfo packetInfo = incomingSctpPackets.poll(100, TimeUnit.MILLISECONDS);
                if (packetInfo == null) {
                    continue;
                }
                if (sctpSocket == null) {
                    logger.error("Incoming SCTP data received but SCTP socket is null");
                } else {
                    RawPacket rp = PacketExtensionsKt.toRawPacket(packetInfo.getPacket());
                    sctpSocket.onConnIn(rp.getBuffer(), rp.getOffset(), rp.getLength());
                }
            } catch (InterruptedException e) {
                logger.error("SctpConnection " + id + " thread interrupted while trying to read, shutting down");
                stop();
            } catch (IOException e) {
                logger.error("SctpConnection " + id + " IO exception: " + e);
            }
        }
    }

    /**
     * Handle incoming SCTP data after it's been processed by the SCTP stack
     */
    @Override
    public void onSctpPacket(
            byte[] data,
            /*
             * Unique stream identifier
             */
            int streamId,
            /*
             * This value contains the stream sequence number that the
             * remote endpoint placed in the DATA chunk.  For fragmented
             * messages, this is the same number for all deliveries of the
             * message (if more than one recvmsg() is needed to read the
             * message).
             */
            int streamSequenceNumber,
            /*
             * a Transmission Sequence Number (TSN) that was assigned to one
             * of the SCTP DATA chunks.
             */
            int transmissionSequenceNumber,
            /*
             * This value is the same information that was passed by the
             *  upper layer in the peer application (used by WebRTC as a message type field)
             */
            long ppid,
            int context,
            int flags) {
    }
}
