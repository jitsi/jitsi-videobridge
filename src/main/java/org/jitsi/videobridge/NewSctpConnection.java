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

import org.bouncycastle.crypto.tls.*;
import org.ice4j.socket.*;
import org.ice4j.util.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.dtls.*;
import org.jitsi.nlj.util.*;
import org.jitsi.sctp4j.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.packetlogging.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * Class is a transport layer for WebRTC data channels. It consists of SCTP
 * connection running on top of ICE/DTLS layer. Manages WebRTC data channels.
 * See http://tools.ietf.org/html/draft-ietf-rtcweb-data-channel-08 for more
 * info on WebRTC data channels.
 * <p>
 * Control protocol:
 * http://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-03
 * </p>
 *
 * FIXME handle closing of data channels(SCTP stream reset)
 *
 * @author Pawel Domas
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class NewSctpConnection
    extends SctpConnection
    implements SctpDataCallback,
               SctpSocket.NotificationListener
{
    /**
     * Generator used to track debug IDs.
     */
    private static int debugIdGen = -1;

    /**
     * DTLS transport buffer size.
     * Note: randomly chosen.
     */
    private static final int DTLS_BUFFER_SIZE = 2048;

    /**
     * Switch used for debugging SCTP traffic purposes.
     * FIXME to be removed
     */
    private static final boolean LOG_SCTP_PACKETS = false;

    /**
     * The {@link Logger} used by the {@link NewSctpConnection} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(NewSctpConnection.class);

    /**
     * SCTP transport buffer size.
     */
    private static final int SCTP_BUFFER_SIZE = DTLS_BUFFER_SIZE - 13;

    /**
     * The object used to synchronize access to fields specific to this
     * {@link NewSctpConnection}. We use it to avoid synchronizing on {@code this}
     * which is a {@link Channel}.
     */
    private final Object syncRoot = new Object();

    /**
     * The {@link PacketQueue} instance in which we place packets coming from
     * the SCTP stack which are to be sent via {@link #transformer}.
     */
    private final RawPacketQueue packetQueue;

    /**
     * The instance which we use to handle packets read from
     * {@link #packetQueue}.
     */
    private NewHandler newHandler = new NewHandler();

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    LinkedBlockingQueue<PacketInfo> incomingSctpPackets;

    DTLSTransport dtlsTransport;

    /**
     * Initializes a new <tt>SctpConnection</tt> instance.
     *
     * @param id the string identifier of this connection instance
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param endpoint the <tt>Endpoint</tt> of newly created instance
     * @param remoteSctpPort the SCTP port used by remote peer
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>SctpConnection</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     */
    public NewSctpConnection(
            String id,
            Content content,
            AbstractEndpoint endpoint,
            int remoteSctpPort,
            String channelBundleId,
            Boolean initiator)
    {
        super(id,
              content,
              endpoint,
              remoteSctpPort,
              channelBundleId,
              initiator);

        logger
            = Logger.getLogger(classLogger, content.getConference().getLogger());
        packetQueue
            = new RawPacketQueue(
                false,
                getClass().getSimpleName() + "-" + endpoint.getID(),
                newHandler);
    }

    @Override
    void initialize() throws IOException
    {
        super.initialize();
        incomingSctpPackets = ((IceDtlsTransportManager)getTransportManager()).sctpAppPackets;
        this.dtlsTransport = ((IceDtlsTransportManager)getTransportManager()).dtlsTransport;
    }

    @Override
    protected void maybeStartStream()
            throws IOException
    {
        System.out.println("BRIAN: sctpconnection maybestartstream");
        // connector
        final StreamConnector connector = getStreamConnector();

        synchronized (syncRoot)
        {
            if (started)
                return;

            threadPool.execute(
                    new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                Sctp.init();

                                runOnDtlsTransport(connector);
                            }
                            catch (IOException e)
                            {
                                logger.error(e, e);
                            }
                            finally
                            {
                                try
                                {
                                    Sctp.finish();
                                }
                                catch (IOException e)
                                {
                                    logger.error(
                                            "Failed to shutdown SCTP stack",
                                            e);
                                }
                            }
                        }
                    });

            started = true;
        }
    }

    @Override
    protected void runOnDtlsTransport(StreamConnector connector)
        throws IOException
    {
        System.out.println("BRIAN: starting dtls transport thread");
        byte[] receiveBuffer = new byte[SCTP_BUFFER_SIZE];

        synchronized (syncRoot)
        {
            // FIXME local SCTP port is hardcoded in bridge offer SDP (Jitsi
            // Meet)
            sctpSocket = Sctp.createSocket(5000);
            assocIsUp = false;
            acceptedIncomingConnection = false;
        }

        // Implement output network link for SCTP stack on DTLS transport
        sctpSocket.setLink(new NetworkLink()
        {
            @Override
            public void onConnOut(SctpSocket s, byte[] packet)
                throws IOException
            {
                // Send through DTLS transport. Add to the queue in order to
                // make sure we don't block the thread which executes this.
                packetQueue.add(packet, 0, packet.length);
            }
        });
        System.out.println(
                "Connecting SCTP to port: " + remoteSctpPort + " to "
                        + getEndpoint().getID());

        sctpSocket.setNotificationListener(this);
        sctpSocket.listen();

        // Notify that from now on SCTP connection is considered functional
        sctpSocket.setDataCallback(this);

        // FIXME manage threads
        threadPool.execute(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        SctpSocket sctpSocket = null;
                        try
                        {
                            // sctpSocket is set to null on close
                            sctpSocket = NewSctpConnection.this.sctpSocket;
                            while (sctpSocket != null)
                            {
                                if (sctpSocket.accept())
                                {
                                    acceptedIncomingConnection = true;
                                    logger.info("SCTP socket accepted for "
                                            + "endpoint "
                                            + getEndpoint().getID());
                                    break;
                                }
                                Thread.sleep(100);
                                sctpSocket = NewSctpConnection.this.sctpSocket;
                            }
                            if (isReady())
                            {
                                notifySctpConnectionReady();
                            }
                        }
                        catch (Exception e)
                        {
                            logger.error("Error accepting SCTP connection", e);
                        }

                        if (sctpSocket == null && logger.isInfoEnabled())
                        {
                            logger.info("SctpConnection " + getID() + " closed"
                                        + " before SctpSocket accept()-ed.");
                        }
                    }
                });

        // Receive loop, breaks when SCTP socket is closed
        try
        {
            do
            {
//                int bytesReceived = dtlsSenderReceiver.receive(receiveBuffer, 0, receiveBuffer.length, 10);
                PacketInfo packetInfo = null;
                try
                {
                    packetInfo = incomingSctpPackets.poll(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                if (packetInfo == null) {
                    continue;
                }
//                System.out.println("BRIAN: NEWSCTPCONNECTION GOT INCOMING SCTP PACKET");

                RawPacket[] send
                    = {
                        PacketExtensionsKt.toRawPacket(packetInfo.getPacket())
                    };

                // We received data for the SCTP socket, this SctpConnection
                // is still alive
                touch(ActivityType.PAYLOAD);

                if (sctpSocket == null)
                    break;

                // Pass network packet to SCTP stack
                for (RawPacket s : send)
                {
                    sctpSocket.onConnIn(
                            s.getBuffer(), s.getOffset(), s.getLength());
                }
            }
            while (true);
        }
        catch (SocketException ex)
        {
            if (!"Socket closed".equals(ex.getMessage())
                && !(ex instanceof SocketClosedException))
            {
                throw ex;
            }
        }
        finally
        {
            // Eventually, close the socket although it should happen in
            // expire().
            closeStream();
        }
    }

    /**
     * A {@link PacketQueue.PacketHandler} which sends packets
     * over DTLS.
     */
    private class NewHandler implements PacketQueue.PacketHandler<RawPacket>
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public boolean handlePacket(RawPacket pkt)
        {
            if (pkt == null)
            {
                return true;
            }

            //TODO: INTEGRATE THIS WITH THE SEND CHAIN
//            DtlsPacketTransformer transformer = null; //NewSctpConnection.this.transformer;
//            if (transformer == null)
//            {
//                logger.error("Cannot send SCTP packet, DTLS transformer is null");
//                return false;
//            }

            if (dtlsTransport == null) {
                dtlsTransport = ((IceDtlsTransportManager)getTransportManager()).dtlsTransport;
            }

            try
            {
//                System.out.println("NewSctpConnection sending sctp packet");
                dtlsTransport.send(
                        pkt.getBuffer(), pkt.getOffset(), pkt.getLength());
            } catch (IOException e)
            {
                e.printStackTrace();
            }

//            transformer.sendApplicationData(
//                pkt.getBuffer(), pkt.getOffset(), pkt.getLength());

            return true;
        }
    };
}
