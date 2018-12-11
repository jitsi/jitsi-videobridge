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

import org.bouncycastle.crypto.tls.DTLSClientProtocol
import org.bouncycastle.crypto.tls.TlsClient
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import java.security.SecureRandom

class DtlsClientStack(
        private val dtlsClientProtocol: DTLSClientProtocol = DTLSClientProtocol(SecureRandom())
) : DtlsStack() {
    private var tlsClient: TlsClient? = null

    fun connect(tlsClient: TlsClient) {
        this.tlsClient = tlsClient
        try {
            dtlsTransport = dtlsClientProtocol.connect(this.tlsClient, this)
            logger.cinfo { "DTLS handshake finished" }
            handshakeCompleteHandler((tlsClient as TlsClientImpl).getContext())
        } catch (e: Exception) {
            logger.cerror{ "Error during DTLS connection: $e" }
            throw e
        }
    }

    override fun getChosenSrtpProtectionProfile(): Int = (tlsClient as? TlsClientImpl)?.chosenSrtpProtectionProfile ?: 0
}
