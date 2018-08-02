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
package org.jitsi.nlj.transform2.module

import org.bouncycastle.crypto.tls.AlertDescription
import org.bouncycastle.crypto.tls.Certificate
import org.bouncycastle.crypto.tls.CertificateRequest
import org.bouncycastle.crypto.tls.CipherSuite
import org.bouncycastle.crypto.tls.DTLSClientProtocol
import org.bouncycastle.crypto.tls.DTLSTransport
import org.bouncycastle.crypto.tls.DatagramTransport
import org.bouncycastle.crypto.tls.DefaultTlsClient
import org.bouncycastle.crypto.tls.DefaultTlsSignerCredentials
import org.bouncycastle.crypto.tls.HashAlgorithm
import org.bouncycastle.crypto.tls.ProtocolVersion
import org.bouncycastle.crypto.tls.SRTPProtectionProfile
import org.bouncycastle.crypto.tls.SignatureAlgorithm
import org.bouncycastle.crypto.tls.SignatureAndHashAlgorithm
import org.bouncycastle.crypto.tls.TlsAuthentication
import org.bouncycastle.crypto.tls.TlsClient
import org.bouncycastle.crypto.tls.TlsCredentials
import org.bouncycastle.crypto.tls.TlsSRTPUtils
import org.bouncycastle.crypto.tls.TlsUtils
import org.bouncycastle.crypto.tls.UseSRTPData
import org.jitsi.nlj.dtls.CertificateInfo
import org.jitsi.nlj.dtls.DtlsUtils
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import java.lang.Math.min
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Duration
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit


/**
 * Acts as a DTLS client and handles the DTLS negotiation
 */
abstract class DtlsStack {
    companion object {
        private var certificate: CertificateInfo = DtlsUtils.generateCertificateInfo()
        private val syncRoot: Any = Any()
        fun getCertificate(): CertificateInfo {
            synchronized (DtlsStack.syncRoot) {
                val expiration = Duration.ofDays(1).toMillis()
                if (DtlsStack.certificate.timestamp + expiration < System.currentTimeMillis()) {
                    DtlsStack.certificate = DtlsUtils.generateCertificateInfo()
                }
                return DtlsStack.certificate
            }
        }
    }

    abstract fun connect(): Future<DTLSTransport>

    /**
     * Map of a hash function String to the fingerprint
     * TODO: do we need to handle multiple remove fingerprint hash function types?
     */
    var remoteFingerprints: Map<String, String> = mapOf()

    val localFingerprint: String
        get() = DtlsStack.getCertificate().localFingerprint

    val localFingerprintHashFunction: String
        get() = DtlsStack.getCertificate().localFingerprintHashFunction
}

class DtlsClientStack @JvmOverloads constructor(
    private val transport: DatagramTransport,
    private val tlsClient: TlsClient = TlsClientImpl(),
    private val dtlsClientProtocol: DTLSClientProtocol = DTLSClientProtocol(SecureRandom()),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : DtlsStack() {
    override fun connect(): Future<DTLSTransport> {
       return executor.submit(
           Callable<DTLSTransport> { dtlsClientProtocol.connect(tlsClient, transport) }
       )
    }
}

class TlsClientImpl : DefaultTlsClient() {
    private var clientCredentials: TlsCredentials? = null
    override fun getAuthentication(): TlsAuthentication {
        return object : TlsAuthentication {
            override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials {
//                println("BRIAN: tls client#getClientCredentials")
                // TODO: Do this via some sort of observer instead? Does this need to be updated it if survives across
                // a certificate expiration?
                // NOTE: doing this in init didn't work because context won't be set yet
                if (clientCredentials == null) {
                    val certificateInfo = DtlsStack.getCertificate()
                    clientCredentials = DefaultTlsSignerCredentials(
                        context,
                        certificateInfo.certificate,
                        certificateInfo.keyPair.private,
                        SignatureAndHashAlgorithm(HashAlgorithm.sha1, SignatureAlgorithm.rsa)
                    )
                }
                return clientCredentials!!
            }

            override fun notifyServerCertificate(certificate: Certificate) {
//                println("BRIAN: tls client#notifyServerCertificate")
                // TODO: Need access to the remote fingerprints here
            }
        }
    }

    override fun getClientExtensions(): Hashtable<*, *> {
        var clientExtensions = super.getClientExtensions();
        if (TlsSRTPUtils.getUseSRTPExtension(clientExtensions) == null) {
            if (clientExtensions == null) {
                clientExtensions = Hashtable<Int, ByteArray>()
            }

            TlsSRTPUtils.addUseSRTPExtension(
                clientExtensions,
                UseSRTPData(
                    intArrayOf(
                        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
                        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32
                    ),
                    TlsUtils.EMPTY_BYTES
                )
            )
        }

        return clientExtensions
    }

    override fun getClientVersion(): ProtocolVersion = ProtocolVersion.DTLSv10;

    override fun getMinimumVersion(): ProtocolVersion = ProtocolVersion.DTLSv10;

    override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) {
        val stack = with(StringBuffer()) {
            val e = Exception()
            for (el in e.stackTrace) {
                appendln(el.toString())
            }
            toString()
        }
//        println("BRIAN: dtls client raised alert: ${AlertDescription.getText(alertDescription)} $message $cause \n $stack")
    }

    override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
//        println("BRIAN: dtls received alert: ${AlertDescription.getText(alertDescription)}")
    }
}

/**
 * [DatagramTransportImpl] is an implementation of [DatagramTransport] which reads its
 * incoming data from a thread-safe queue.  Incoming DTLS data should be written via
 * [enqueuePackets].
 */
class DatagramTransportImpl(
    private val inputQueue: BlockingQueue<Packet>,
    private val outputQueue: BlockingQueue<Packet>,
    private val mtu: Int = 1500
) : DatagramTransport {
//    private val queue = LinkedBlockingQueue<Packet>()

    //TODO: instead of adding this new API call (and therefore deviating a bit
    // from DatagramTransport's interface) we could pass the queue in to this
    // instead and have writers write to it directly?)
//    fun enqueuePackets(p: List<Packet>) {
//        queue.addAll(p)
//    }

    override fun receive(buf: ByteArray, off: Int, length: Int, waitMillis: Int): Int {
//        println("BRIAN: dtls transport trying to receive packet")
        val packet = inputQueue.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS) ?: return 0
//        println("BRIAN: dtls transport received a packet of size ${packet.buf.limit()}, given buf length was: $length")
        System.arraycopy(packet.buf.array(), 0, buf, off, min(length, packet.buf.limit()))
        return packet.buf.limit()
    }

    override fun send(buf: ByteArray, off: Int, length: Int) {
//        println("BRIAN: dtls transport sending packet")
        val p = UnparsedPacket(ByteBuffer.wrap(buf, off, length))
        outputQueue.add(p)
    }

    /**
     * No-op
     */
    override fun close() {}

    /**
     * Receive limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
     */
    override fun getReceiveLimit(): Int = mtu - 20 - 8

    /**
     * Send limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
     */
    override fun getSendLimit(): Int = mtu - 84 - 8
}

/**
 * Responsible
 */
class DtlsModule : Module("DtlsModule") {
    override fun doProcessPackets(p: List<Packet>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
