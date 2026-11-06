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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.ClientCertificateType
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ExporterLabel
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.NamedGroup
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsContext
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.UseSRTPData
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.srtp.SrtpConfig
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import java.util.Hashtable
import java.util.Vector
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.concurrent.thread

/**
 * Verifies that a [DtlsStack] with DTLS 1.3 and the post-quantum key exchange enabled (the defaults) negotiates
 * correctly with a remote endpoint which supports less, in both the client and the server role, and that the two
 * sides still agree on the SRTP keying material:
 * - Falls back to DTLS 1.2 when the remote endpoint only supports DTLS 1.2.
 * - Falls back to a classical key exchange (X25519) when the remote endpoint supports DTLS 1.3 but not the
 *   X25519MLKEM768 hybrid.
 *
 * The remote endpoint is a plain BouncyCastle DTLS peer (whose default supported groups do not include the hybrid),
 * standing in for e.g. a browser. It can't be another [DtlsStack], because the DTLS versions and groups are
 * configured globally, so two stacks in the same JVM always agree on them.
 */
class DtlsVersionNegotiationTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val logger = StdoutLogger(_level = Level.OFF)

    /** The result of a handshake on one side of the connection. */
    private class HandshakeResult(
        val protocolVersion: ProtocolVersion?,
        val group: Int?,
        val srtpProtectionProfile: Int,
        val srtpKeyingMaterial: ByteArray
    )

    /**
     * Runs a handshake between a [DtlsStack] in the server role and a BouncyCastle peer supporting [peerVersions].
     */
    private fun stackAsServer(peerVersions: Array<ProtocolVersion>): Pair<HandshakeResult, HandshakeResult> {
        val stack = DtlsStack(logger).apply { actAsServer() }
        val peer = BcPeer(stack, peerVersions)

        val stackResult = CompletableFuture<HandshakeResult>()
        stack.eventHandler = object : DtlsStack.EventHandler {
            override fun handshakeComplete(
                chosenSrtpProtectionProfile: Int,
                tlsRole: TlsRole,
                keyingMaterial: ByteArray
            ) {
                tlsRole shouldBe TlsRole.SERVER
                stackResult.complete(
                    HandshakeResult(
                        stack.negotiatedProtocolVersion,
                        stack.negotiatedGroup,
                        chosenSrtpProtectionProfile,
                        keyingMaterial
                    )
                )
            }
        }

        val serverThread = thread { stack.start() }
        val peerResult = peer.connect()
        serverThread.join(10_000)

        return Pair(stackResult.get(5, TimeUnit.SECONDS), peerResult)
    }

    /**
     * Runs a handshake between a [DtlsStack] in the client role and a BouncyCastle peer supporting [peerVersions].
     */
    private fun stackAsClient(peerVersions: Array<ProtocolVersion>): Pair<HandshakeResult, HandshakeResult> {
        val stack = DtlsStack(logger).apply { actAsClient() }
        val peer = BcPeer(stack, peerVersions)

        val stackResult = CompletableFuture<HandshakeResult>()
        stack.eventHandler = object : DtlsStack.EventHandler {
            override fun handshakeComplete(
                chosenSrtpProtectionProfile: Int,
                tlsRole: TlsRole,
                keyingMaterial: ByteArray
            ) {
                tlsRole shouldBe TlsRole.CLIENT
                stackResult.complete(
                    HandshakeResult(
                        stack.negotiatedProtocolVersion,
                        stack.negotiatedGroup,
                        chosenSrtpProtectionProfile,
                        keyingMaterial
                    )
                )
            }
        }

        val peerFuture = CompletableFuture<HandshakeResult>()
        val serverThread = thread {
            try {
                peerFuture.complete(peer.accept())
            } catch (t: Throwable) {
                peerFuture.completeExceptionally(t)
            }
        }
        stack.start()
        serverThread.join(10_000)

        return Pair(stackResult.get(5, TimeUnit.SECONDS), peerFuture.get(5, TimeUnit.SECONDS))
    }

    private fun assertNegotiated(
        results: Pair<HandshakeResult, HandshakeResult>,
        version: ProtocolVersion,
        group: Int?
    ) {
        val (stackResult, peerResult) = results
        stackResult.protocolVersion shouldBe version
        peerResult.protocolVersion shouldBe version
        stackResult.group shouldBe group
        peerResult.group shouldBe group
        stackResult.srtpProtectionProfile shouldBe peerResult.srtpProtectionProfile
        stackResult.srtpKeyingMaterial.contentEquals(peerResult.srtpKeyingMaterial) shouldBe true
    }

    init {
        DtlsConfig.config.dtls13Enabled shouldBe true
        DtlsConfig.config.offerPostQuantumKeyExchange shouldBe true

        val dtls12Only = ProtocolVersion.DTLSv12.only()
        val dtls13 = ProtocolVersion.DTLSv13.downTo(ProtocolVersion.DTLSv12)

        context("A DTLS server with a client that only supports DTLS 1.2") {
            should("negotiate DTLS 1.2 and agree on the SRTP keying material") {
                // The named group is only recorded for DTLS 1.3 handshakes.
                assertNegotiated(stackAsServer(dtls12Only), ProtocolVersion.DTLSv12, null)
            }
        }
        context("A DTLS client with a server that only supports DTLS 1.2") {
            should("negotiate DTLS 1.2 and agree on the SRTP keying material") {
                assertNegotiated(stackAsClient(dtls12Only), ProtocolVersion.DTLSv12, null)
            }
        }
        context("A DTLS server with a DTLS 1.3 client that doesn't support the post-quantum key exchange") {
            should("negotiate DTLS 1.3 with X25519 and agree on the SRTP keying material") {
                assertNegotiated(stackAsServer(dtls13), ProtocolVersion.DTLSv13, NamedGroup.x25519)
            }
        }
        context("A DTLS client with a DTLS 1.3 server that doesn't support the post-quantum key exchange") {
            should("negotiate DTLS 1.3 with X25519 and agree on the SRTP keying material") {
                assertNegotiated(stackAsClient(dtls13), ProtocolVersion.DTLSv13, NamedGroup.x25519)
            }
        }
    }

    /**
     * A plain BouncyCastle DTLS-SRTP peer supporting [versions] (and BouncyCastle's default key exchange groups,
     * which do not include the post-quantum hybrid), wired directly to a [DtlsStack]. It uses its own certificate,
     * and the stack is told the peer's fingerprint (the peer itself verifies nothing, this is a test).
     */
    private class BcPeer(private val stack: DtlsStack, private val versions: Array<ProtocolVersion>) {
        private val certificateInfo = DtlsUtils.generateCertificateInfo()
        private val incoming = LinkedBlockingQueue<ByteArray>()

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
                val length = minOf(len, data.size)
                System.arraycopy(data, 0, buf, off, length)
                return length
            }
            override fun send(buf: ByteArray, off: Int, len: Int) = stack.processIncomingProtocolData(buf, off, len)
            override fun close() {}
        }

        private fun signerCredentials(
            context: TlsContext,
            certificateRequestContext: ByteArray? = null
        ): TlsCredentialedSigner = BcDefaultTlsCredentialedSigner(
            TlsCryptoParameters(context),
            context.crypto as BcTlsCrypto,
            PrivateKeyFactory.createKey(certificateInfo.keyPair.private.encoded),
            certificateInfo.certificateFor(context, certificateRequestContext),
            SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
        )

        private fun result(context: TlsContext, profile: Int): HandshakeResult {
            val info = SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(profile)
            return HandshakeResult(
                context.securityParameters.negotiatedVersion,
                context.securityParameters.negotiatedGroup.takeIf { it >= 0 },
                profile,
                context.exportKeyingMaterial(
                    ExporterLabel.dtls_srtp,
                    null,
                    2 * (info.cipherKeyLength + info.cipherSaltLength)
                )
            )
        }

        fun connect(): HandshakeResult {
            var result: HandshakeResult? = null
            val client = object : DefaultTlsClient(BC_TLS_CRYPTO) {
                private var chosenProfile = 0
                override fun getSupportedVersions(): Array<ProtocolVersion> = versions
                override fun getCipherSuites() = DtlsConfig.config.cipherSuites.toIntArray()
                override fun getClientExtensions(): Hashtable<*, *> {
                    val extensions = super.getClientExtensions() ?: Hashtable<Int, ByteArray>()
                    TlsSRTPUtils.addUseSRTPExtension(
                        extensions,
                        UseSRTPData(SrtpConfig.protectionProfiles.toIntArray(), TlsUtils.EMPTY_BYTES)
                    )
                    return extensions
                }
                override fun processServerExtensions(serverExtensions: Hashtable<*, *>?) {
                    super.processServerExtensions(serverExtensions)
                    chosenProfile = TlsSRTPUtils.getUseSRTPExtension(serverExtensions).protectionProfiles.single()
                }
                override fun getAuthentication() = object : TlsAuthentication {
                    override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {}
                    override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials =
                        signerCredentials(context, certificateRequest.certificateRequestContext)
                }
                override fun notifyHandshakeComplete() {
                    super.notifyHandshakeComplete()
                    result = result(context, chosenProfile)
                }
            }
            DTLSClientProtocol().connect(client, transport)
            return result!!
        }

        fun accept(): HandshakeResult {
            var result: HandshakeResult? = null
            val server = object : DefaultTlsServer(BC_TLS_CRYPTO) {
                private var chosenProfile = 0
                override fun getSupportedVersions(): Array<ProtocolVersion> = versions
                override fun getCipherSuites() = DtlsConfig.config.cipherSuites.toIntArray()
                override fun getECDSASignerCredentials(): TlsCredentialedSigner = signerCredentials(context)
                override fun getCredentials(): TlsCredentials =
                    if (TlsUtils.isTLSv13(context)) getECDSASignerCredentials() else super.getCredentials()
                override fun processClientExtensions(clientExtensions: Hashtable<*, *>?) {
                    super.processClientExtensions(clientExtensions)
                    chosenProfile = DtlsUtils.chooseSrtpProtectionProfile(
                        SrtpConfig.protectionProfiles,
                        TlsSRTPUtils.getUseSRTPExtension(clientExtensions).protectionProfiles.asIterable()
                    )
                }
                override fun getServerExtensions(): Hashtable<*, *> {
                    val extensions = super.getServerExtensions() ?: Hashtable<Int, ByteArray>()
                    TlsSRTPUtils.addUseSRTPExtension(
                        extensions,
                        UseSRTPData(intArrayOf(chosenProfile), TlsUtils.EMPTY_BYTES)
                    )
                    return extensions
                }
                override fun getCertificateRequest(): CertificateRequest {
                    val sigAlgs = Vector<SignatureAndHashAlgorithm>(1)
                    sigAlgs.add(SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa))
                    return if (TlsUtils.isTLSv13(context)) {
                        CertificateRequest(TlsUtils.EMPTY_BYTES, sigAlgs, null, null)
                    } else {
                        CertificateRequest(shortArrayOf(ClientCertificateType.ecdsa_sign), sigAlgs, null)
                    }
                }
                override fun notifyClientCertificate(clientCertificate: Certificate?) {}
                override fun notifyHandshakeComplete() {
                    super.notifyHandshakeComplete()
                    result = result(context, chosenProfile)
                }
            }
            DTLSServerProtocol().accept(server, transport)
            return result!!
        }
    }
}
