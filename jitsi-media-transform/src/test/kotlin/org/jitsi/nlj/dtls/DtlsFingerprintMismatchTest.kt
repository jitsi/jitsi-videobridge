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
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsFatalAlertReceived
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.srtp.TlsRole
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.concurrent.thread

/**
 * Verifies that a [DtlsStack] whose remote fingerprints do not match the peer's certificate (or which has none)
 * aborts the handshake with a `bad_certificate` alert, in either role, and that the peer receives that alert (rather
 * than retransmitting until its handshake timeout).
 */
class DtlsFingerprintMismatchTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val logger = StdoutLogger(_level = Level.OFF)

    /** A syntactically valid SHA-256 fingerprint which matches no certificate. */
    private val wrongFingerprint = List(32) { "00" }.joinToString(":")

    /**
     * Runs a handshake between two stacks where [verifier] has [verifierFingerprints] (which do not match [peer]'s
     * certificate) instead of the peer's fingerprint. Returns the exception the verifier's [DtlsStack.start] threw
     * and the one the peer got.
     *
     * With DTLS 1.3 a client sends its Finished in the same flight as its certificate, so when the verifier is the
     * server the peer's handshake completes before the verifier rejects the certificate, and the alert reaches the
     * peer after its [DtlsStack.start] returned: it then surfaces from [DtlsStack.processIncomingProtocolData].
     */
    private fun handshake(
        verifier: DtlsStack,
        peer: DtlsStack,
        verifierFingerprints: Map<String, List<String>> = mapOf("sha-256" to listOf(wrongFingerprint))
    ): Pair<TlsFatalAlert, Throwable> {
        verifier.remoteFingerprints = verifierFingerprints
        peer.remoteFingerprints = mapOf(verifier.localFingerprintHashFunction to listOf(verifier.localFingerprint))

        val peerFailure = CompletableFuture<Throwable>()
        verifier.outgoingDataHandler = object : DtlsStack.OutgoingDataHandler {
            override fun sendData(data: ByteArray, off: Int, len: Int) {
                try {
                    peer.processIncomingProtocolData(data, off, len)
                } catch (e: TlsFatalAlertReceived) {
                    // The alert arrived after the peer's handshake had completed (DTLS 1.3, see above).
                    peerFailure.complete(e)
                }
            }
        }
        peer.outgoingDataHandler = object : DtlsStack.OutgoingDataHandler {
            override fun sendData(data: ByteArray, off: Int, len: Int) =
                verifier.processIncomingProtocolData(data, off, len)
        }

        val verifierCompleted = AtomicBoolean()
        verifier.eventHandler = object : DtlsStack.EventHandler {
            override fun handshakeComplete(
                chosenSrtpProtectionProfile: Int,
                tlsRole: TlsRole,
                keyingMaterial: ByteArray
            ) {
                verifierCompleted.set(true)
            }
        }

        val peerThread = thread {
            try {
                peer.start()
            } catch (t: Throwable) {
                peerFailure.complete(t)
            }
        }
        try {
            val verifierFailure = shouldThrow<TlsFatalAlert> { verifier.start() }
            peerThread.join(10_000)

            verifierCompleted.get() shouldBe false
            return Pair(verifierFailure, peerFailure.get(5, TimeUnit.SECONDS))
        } finally {
            verifier.close()
            peer.close()
            peerThread.join(10_000)
        }
    }

    /** Asserts that both sides failed with `bad_certificate`, the verifier for the reason starting with [cause]. */
    private fun assertBadCertificate(failures: Pair<TlsFatalAlert, Throwable>, cause: String) {
        val (verifierFailure, peerFailure) = failures
        verifierFailure.alertDescription shouldBe AlertDescription.bad_certificate
        verifierFailure.cause.shouldBeInstanceOf<DtlsUtils.DtlsException>().message!! shouldStartWith cause
        // The alert reached the peer, so it failed promptly instead of waiting for its handshake timeout.
        peerFailure.shouldBeInstanceOf<TlsFatalAlertReceived>().alertDescription shouldBe
            AlertDescription.bad_certificate
    }

    init {
        context("A client with a wrong fingerprint for the server") {
            should("abort the handshake with bad_certificate") {
                val client = DtlsStack(logger).apply { actAsClient() }
                val server = DtlsStack(logger).apply { actAsServer() }
                assertBadCertificate(handshake(verifier = client, peer = server), "None of the fingerprints")
            }
        }
        context("A server with a wrong fingerprint for the client") {
            should("abort the handshake with bad_certificate") {
                val client = DtlsStack(logger).apply { actAsClient() }
                val server = DtlsStack(logger).apply { actAsServer() }
                assertBadCertificate(handshake(verifier = server, peer = client), "None of the fingerprints")
            }
        }
        context("A server with no fingerprint for the client yet") {
            // The handshake outran the signaling of the fingerprint.
            should("abort the handshake with bad_certificate") {
                val client = DtlsStack(logger).apply { actAsClient() }
                val server = DtlsStack(logger).apply { actAsServer() }
                assertBadCertificate(
                    handshake(verifier = server, peer = client, verifierFingerprints = mapOf()),
                    "No fingerprint declared over the signaling path"
                )
            }
        }
    }
}
