/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.TlsContext
import org.bouncycastle.tls.TlsUtils
import java.security.KeyPair

/**
 * Store various information about a generated Certificate, including:
 * The [KeyPair] used to build and sign it, as well as the fingerprint
 * hash function and the calculated fingerprint we use (which is transmitted
 * over the signaling channel so the certificate can be verified).  We also
 * store the time at which this certificate was created so we can refresh it
 * appropriately.
 */
data class CertificateInfo(
    val keyPair: KeyPair,
    val certificate: org.bouncycastle.tls.Certificate,
    val localFingerprintHashFunction: String,
    val localFingerprint: String,
    val creationTimestampMs: Long
) {
    /**
     * The [certificate] in the form required by the (D)TLS version negotiated in [context]. A (D)TLS 1.3 Certificate
     * message carries a certificate_request_context (RFC 8446 4.4.2): empty for a server, and for a client the one
     * from the CertificateRequest ([certificateRequestContext]). It is absent in (D)TLS 1.2, and BouncyCastle
     * refuses to encode a certificate whose form doesn't match the negotiated version.
     */
    fun certificateFor(context: TlsContext, certificateRequestContext: ByteArray? = null): Certificate =
        if (TlsUtils.isTLSv13(context)) {
            Certificate(certificateRequestContext ?: TlsUtils.EMPTY_BYTES, certificate.certificateEntryList)
        } else {
            certificate
        }
}
