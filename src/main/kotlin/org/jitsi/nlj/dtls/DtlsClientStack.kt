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
import org.bouncycastle.crypto.tls.DTLSTransport
import org.bouncycastle.crypto.tls.DatagramTransport
import org.bouncycastle.crypto.tls.TlsClient
import org.bouncycastle.crypto.tls.TlsContext
import org.jitsi.nlj.util.NameableThreadFactory
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DtlsClientStack @JvmOverloads constructor(
    private val dtlsClientProtocol: DTLSClientProtocol = DTLSClientProtocol(SecureRandom())
) : DtlsStack() {
    private var tlsClient: TlsClient? = null
    private var datagramTransport: DatagramTransport? = null
    private var subscribers = mutableListOf<(DTLSTransport, TlsContext) -> Unit>()
    private val logger = getLogger(this.javaClass)

    override fun connect(tlsClient: TlsClient, datagramTransport: DatagramTransport) {
        this.tlsClient = tlsClient
        this.datagramTransport = datagramTransport
        try {
            val dtlsTransport = dtlsClientProtocol.connect(this.tlsClient, this.datagramTransport)
            logger.cinfo { "BRIAN: dtls handshake finished" }
            subscribers.forEach { it(dtlsTransport, (tlsClient as TlsClientImpl).getContext()) }
        } catch (e: Exception) {
            logger.cerror{ "BRIAN: error during dtls connection: $e" }
        }
    }

    override fun onHandshakeComplete(func: (DTLSTransport, TlsContext) -> Unit) {
        subscribers.add(func)
    }

    //TODO: better way we can get the chosen profile out without having to cast?
    override fun getChosenSrtpProtectionProfile(): Int = (tlsClient as? TlsClientImpl)?.chosenSrtpProtectionProfile ?: 0

    //TODO: same as above
    override fun getTlsContext(): TlsContext? = (tlsClient as? TlsClientImpl)?.getContext()
}

