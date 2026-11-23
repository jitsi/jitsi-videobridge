/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.nlj.dtls

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsContext
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsFatalAlertReceived
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.UseSRTPData
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.srtp.SrtpConfig
import org.jitsi.nlj.srtp.TlsRole
import java.io.IOException
import java.util.Hashtable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.concurrent.thread

/**
 * Verifies that a [DtlsStack] aborts the handshake with a `handshake_failure` alert, which reaches the peer, when
 * the `use_srtp` extension cannot be agreed on: the peer does not send it at all, or offers (as a client) or picks
 * (as a server) no SRTP protection profile the stack supports. The peer is a plain BouncyCastle DTLS peer, since
 * another [DtlsStack] always sends the extension with the configured profiles.
 */
class DtlsUseSrtpFailureTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val logger = StdoutLogger(_level = Level.OFF)

    /** What the BouncyCastle peer does about the `use_srtp` extension. */
    private enum class UseSrtp {
        /** Does not send the extension. */
        NONE,

        /** Sends the extension with a protection profile the stack does not support. */
        UNSUPPORTED_PROFILE
    }

    /**
     * Runs a handshake between a [DtlsStack] (as server if [asServer], else as client) and a BouncyCastle peer which
     * handles `use_srtp` per [peerUseSrtp]. Returns the exceptions the stack's [DtlsStack.start] and the peer threw.
     */
    private fun handshake(asServer: Boolean, peerUseSrtp: UseSrtp): Pair<TlsFatalAlert, Throwable> {
        val stack = DtlsStack(logger).apply { if (asServer) actAsServer() else actAsClient() }
        val peer = BcPeer(stack, peerUseSrtp)

        val completed = AtomicBoolean()
        stack.eventHandler = object : DtlsStack.EventHandler {
            override fun handshakeComplete(
                chosenSrtpProtectionProfile: Int,
                tlsRole: TlsRole,
                keyingMaterial: ByteArray
            ) {
                completed.set(true)
            }
        }

        val peerFailure = CompletableFuture<Throwable>()
        val peerThread = thread {
            try {
                if (asServer) peer.connect() else peer.accept()
                peerFailure.completeExceptionally(AssertionError("The peer's handshake completed"))
            } catch (t: Throwable) {
                peerFailure.complete(t)
            }
        }
        try {
            val stackFailure = shouldThrow<TlsFatalAlert> { stack.start() }
            peerThread.join(10_000)

            completed.get() shouldBe false
            return Pair(stackFailure, peerFailure.get(5, TimeUnit.SECONDS))
        } finally {
            stack.close()
            peer.close()
            peerThread.join(10_000)
        }
    }

    /** Asserts that the stack raised `handshake_failure` for a reason containing [reason], and that the peer got it. */
    private fun assertHandshakeFailure(failures: Pair<TlsFatalAlert, Throwable>, reason: String) {
        val (stackFailure, peerFailure) = failures
        stackFailure.alertDescription shouldBe AlertDescription.handshake_failure
        stackFailure.message!! shouldContain reason
        // The alert reached the peer, so it failed promptly instead of waiting for its handshake timeout.
        peerFailure.shouldBeInstanceOf<TlsFatalAlertReceived>().alertDescription shouldBe
            AlertDescription.handshake_failure
    }

    init {
        context("A server with a client that doesn't send use_srtp") {
            should("abort the handshake with handshake_failure") {
                assertHandshakeFailure(handshake(asServer = true, peerUseSrtp = UseSrtp.NONE), "No use_srtp extension")
            }
        }
        context("A server with a client that offers no supported SRTP protection profile") {
            should("abort the handshake with handshake_failure") {
                assertHandshakeFailure(
                    handshake(asServer = true, peerUseSrtp = UseSrtp.UNSUPPORTED_PROFILE),
                    "No common SRTP protection profile"
                )
            }
        }
        context("A client with a server that doesn't send use_srtp") {
            should("abort the handshake with handshake_failure") {
                assertHandshakeFailure(
                    handshake(asServer = false, peerUseSrtp = UseSrtp.NONE),
                    "No use_srtp extension"
                )
            }
        }
        context("A client with a server that picks an unsupported SRTP protection profile") {
            should("abort the handshake with handshake_failure") {
                assertHandshakeFailure(
                    handshake(asServer = false, peerUseSrtp = UseSrtp.UNSUPPORTED_PROFILE),
                    "No common SRTP protection profile"
                )
            }
        }
    }

    /**
     * A plain BouncyCastle DTLS 1.2 peer wired directly to a [DtlsStack], with its own certificate (which the stack
     * is told the fingerprint of; the peer itself verifies nothing, this is a test) and the given `use_srtp`
     * behavior.
     */
    private class BcPeer(private val stack: DtlsStack, private val useSrtp: UseSrtp) {
        private val certificateInfo = DtlsUtils.generateCertificateInfo()
        private val incoming = LinkedBlockingQueue<ByteArray>()

        /** A protection profile value which is not among the configured ones. */
        private val unsupportedProfile = (1..0xFFFF).first { it !in SrtpConfig.protectionProfiles }

        @Volatile
        private var closed = false

        /** Makes the peer's pending and future receives fail, so that a handshake it is still waiting on aborts. */
        fun close() {
            closed = true
            incoming.add(ByteArray(0))
        }

        init {
            stack.remoteFingerprints = mapOf(
                certificateInfo.localFingerprintHashFunction to listOf(certificateInfo.localFingerprint)
            )
            stack.outgoingDataHandler = object : DtlsStack.OutgoingDataHandler {
                override fun sendData(data: ByteArray, off: Int, len: Int) {
                    incoming.add(data.copyOfRange(off, off + len))
                }
            }
        }

        /** The peer's end of the "network": receives what the stack sent, and feeds what it sends into the stack. */
        private val transport = object : DatagramTransport {
            override fun getReceiveLimit(): Int = 1500 - 20 - 8
            override fun getSendLimit(): Int = 1500 - 84 - 8
            override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
                val data = incoming.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS) ?: return -1
                if (closed) {
                    throw IOException("closed")
                }
                val length = minOf(len, data.size)
                System.arraycopy(data, 0, buf, off, length)
                return length
            }
            override fun send(buf: ByteArray, off: Int, len: Int) {
                stack.processIncomingProtocolData(buf, off, len)
            }
            override fun close() {}
        }

        private fun signerCredentials(context: TlsContext): TlsCredentialedSigner = BcDefaultTlsCredentialedSigner(
            TlsCryptoParameters(context),
            context.crypto as BcTlsCrypto,
            PrivateKeyFactory.createKey(certificateInfo.keyPair.private.encoded),
            certificateInfo.certificate,
            SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
        )

        /** Adds the `use_srtp` extension to [extensions] as [useSrtp] says, and returns them. */
        private fun withUseSrtp(extensions: Hashtable<*, *>?): Hashtable<*, *> {
            val result = extensions ?: Hashtable<Int, ByteArray>()
            if (useSrtp == UseSrtp.UNSUPPORTED_PROFILE) {
                TlsSRTPUtils.addUseSRTPExtension(
                    result,
                    UseSRTPData(intArrayOf(unsupportedProfile), TlsUtils.EMPTY_BYTES)
                )
            }
            return result
        }

        fun connect() {
            val client = object : DefaultTlsClient(BC_TLS_CRYPTO) {
                // Fail rather than retransmit forever if the stack side aborts the handshake without an alert.
                override fun getHandshakeTimeoutMillis(): Int = 10_000
                override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
                override fun getCipherSuites() = DtlsConfig.config.cipherSuites.toIntArray()
                override fun getClientExtensions(): Hashtable<*, *> = withUseSrtp(super.getClientExtensions())
                override fun getAuthentication() = object : TlsAuthentication {
                    override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {}
                    override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials =
                        signerCredentials(context)
                }
            }
            DTLSClientProtocol().connect(client, transport)
        }

        fun accept() {
            val server = object : DefaultTlsServer(BC_TLS_CRYPTO) {
                override fun getHandshakeTimeoutMillis(): Int = 10_000
                override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
                override fun getCipherSuites() = DtlsConfig.config.cipherSuites.toIntArray()
                override fun getECDSASignerCredentials(): TlsCredentialedSigner = signerCredentials(context)
                override fun getServerExtensions(): Hashtable<*, *> = withUseSrtp(super.getServerExtensions())
            }
            DTLSServerProtocol().accept(server, transport)
        }
    }
}
