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
package org.jitsi.nlj.dtls

import org.bouncycastle.crypto.tls.DTLSTransport
import org.bouncycastle.crypto.tls.DatagramTransport
import org.bouncycastle.crypto.tls.TlsClient
import org.bouncycastle.crypto.tls.TlsContext
import java.time.Duration

/**
 * Acts as a DTLS client and handles the DTLS negotiation
 * TODO: this is somewhat specific to the client stack (it defines
 * an abstract 'connect' method) since that's what we use right now.
 * If we need to support server as well we may need to tweak the model
 * (server API has 'accept' instead)
 */
abstract class DtlsStack {
    companion object {
        /**
         * Because generating the certificate can be expensive, we generate a single
         * one to be used everywhere which expires in 24 hours (when we'll generate
         * another one).
         */
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

    abstract fun connect(tlsClient: TlsClient, datagramTransport: DatagramTransport)

    abstract fun onHandshakeComplete(func: (DTLSTransport, TlsContext) -> Unit)

    abstract fun getChosenSrtpProtectionProfile(): Int

    abstract fun getTlsContext(): TlsContext?

    /**
     * Map of a hash function String to the fingerprint
     * TODO: do we need to handle multiple remote fingerprint hash function types?
     */
    var remoteFingerprints: Map<String, String> = mapOf()

    val localFingerprint: String
        get() = DtlsStack.getCertificate().localFingerprint

    val localFingerprintHashFunction: String
        get() = DtlsStack.getCertificate().localFingerprintHashFunction
}
