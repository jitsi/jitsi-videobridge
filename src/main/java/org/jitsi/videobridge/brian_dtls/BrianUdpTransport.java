/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.videobridge.brian_dtls;

import org.bouncycastle.crypto.tls.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.rtp.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * @author bbaldino
 */
public class BrianUdpTransport implements DatagramTransport
{
    protected LinkedBlockingQueue<Packet> incomingQueue = new LinkedBlockingQueue<>();
    protected DatagramSocket socket;

    public BrianUdpTransport(DatagramSocket socket) {
        this.socket = socket;
    }

    public void enqueuePacket(Packet p) {
        System.out.println("BRIAN: UDP TRANSPORT GOT PACKET");
        incomingQueue.add(p);
    }

    @Override
    public int receive(byte[] buf, int off, int length, int waitMillis)
    {
        System.out.println("BRIAN: BC TRYING TO READ PACKET FROM TRANSPORT with timeout " + waitMillis + " ms");
        try {
            Packet p = incomingQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
            if (p != null) {
                System.out.println("BRIAN: BC GOT PACKET FROM TRANSPORT");
                System.arraycopy(p.getBuffer().array(), 0, buf, off, p.getBuffer().limit());
                return p.getBuffer().limit();
            } else {
                return 0;
            }
        } catch (InterruptedException e) {
            return 0;
        }
    }

    @Override
    public int getReceiveLimit()
    {
        return 99999;
    }

    @Override
    public void close()
    {

    }

    @Override
    public void send(byte[] bytes, int i, int i1)
    {
        System.out.println("BRIAN: TRYING TO SEND DTLS");
        try {
            socket.send(new DatagramPacket(bytes, i, i1));
        } catch (IOException e) {
            System.out.println("BRIAN: error sending packet: " + e.toString());
        }
    }

//    public void send(byte[] buf, int off, int len)
//            throws IOException
//    {
//        System.out.println("BRIAN: TRYING TO SEND DTLS");
//
//        // If possible, construct a single datagram from multiple DTLS records.
//        if (len >= 13)
//        {
//            short type = TlsUtils.readUint8(buf, off);
//            boolean endOfFlight = false;
//
//            switch (type)
//            {
//                case ContentType.handshake:
//                    short msg_type
//                            = TlsUtils.readUint8(
//                            buf,
//                            off
//                                    + 1 /* type */
//                                    + 2 /* version */
//                                    + 2 /* epoch */
//                                    + 6 /* sequence_number */
//                                    + 2 /* length */);
//
//                    switch (msg_type)
//                    {
//                        case HandshakeType.certificate:
//                        case HandshakeType.certificate_request:
//                        case HandshakeType.certificate_verify:
//                        case HandshakeType.client_key_exchange:
//                        case HandshakeType.server_hello:
//                        case HandshakeType.server_key_exchange:
//                        case HandshakeType.session_ticket:
//                        case HandshakeType.supplemental_data:
//                            endOfFlight = false;
//                            break;
//                        case HandshakeType.client_hello:
//                        case HandshakeType.finished:
//                        case HandshakeType.hello_request:
//                        case HandshakeType.hello_verify_request:
//                        case HandshakeType.server_hello_done:
//                            endOfFlight = true;
//                            break;
//                        default:
//                            endOfFlight = true;
//                            System.out.println("BRIAN: Unknown DTLS handshake message type: " + msg_type);
//                            break;
//                    }
//                    // Do fall through!
//                case ContentType.change_cipher_spec:
//                    synchronized (sendBufSyncRoot)
//                    {
//                        int newSendBufLength = sendBufLength + len;
//                        int sendLimit = getSendLimit();
//
//                        if (newSendBufLength <= sendLimit)
//                        {
//                            if (sendBuf == null)
//                            {
//                                sendBuf = new byte[sendLimit];
//                                sendBufLength = 0;
//                            }
//                            else if (sendBuf.length < sendLimit)
//                            {
//                                byte[] oldSendBuf = sendBuf;
//
//                                sendBuf = new byte[sendLimit];
//                                System.arraycopy(
//                                        oldSendBuf, 0,
//                                        sendBuf, 0,
//                                        Math.min(sendBufLength, sendBuf.length));
//                            }
//
//                            System.arraycopy(buf, off, sendBuf, sendBufLength, len);
//                            sendBufLength = newSendBufLength;
//
//                            if (endOfFlight)
//                                flush();
//                        }
//                        else
//                        {
//                            if (endOfFlight)
//                            {
//                                doSend(buf, off, len);
//                            }
//                            else
//                            {
//                                flush();
//                                send(buf, off, len);
//                            }
//                        }
//                    }
//                    break;
//
//                case ContentType.alert:
//                case ContentType.application_data:
//                default:
//                    doSend(buf, off, len);
//                    break;
//            }
//        }
//        else
//        {
//            doSend(buf, off, len);
//        }
//    }

    @Override
    public int getSendLimit()
    {
        return 99999;
    }

//    private void doSend(byte[] buf, int off, int len)
//            throws IOException
//    {
//        // Do preserve the sequence of sends.
//        flush();
//
//        socket.send(new DatagramPacket(buf, off, len));
//    }

//    private byte[] sendBuf;
//    private int sendBufLength;
//    private final Object sendBufSyncRoot = new Object();

//    private void flush()
//            throws IOException
//    {
//        byte[] buf;
//        int len;
//
//        synchronized (sendBufSyncRoot)
//        {
//            if ((sendBuf != null) && (sendBufLength != 0))
//            {
//                buf = sendBuf;
//                sendBuf = null;
//                len = sendBufLength;
//                sendBufLength = 0;
//            }
//            else
//            {
//                buf = null;
//                len = 0;
//            }
//        }
//        if (buf != null)
//        {
//            doSend(buf, 0, len);
//
//            // Attempt to reduce allocations and garbage collection.
//            synchronized (sendBufSyncRoot)
//            {
//                if (sendBuf == null)
//                    sendBuf = buf;
//            }
//        }
//    }
}
