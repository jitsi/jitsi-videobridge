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
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class DtlsClientStack @JvmOverloads constructor(
    private val dtlsClientProtocol: DTLSClientProtocol = DTLSClientProtocol(SecureRandom()),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : DtlsStack() {
    private var tlsClient: TlsClient? = null
    private var datagramTransport: DatagramTransport? = null

    override fun connect(tlsClient: TlsClient, datagramTransport: DatagramTransport): Future<DTLSTransport> {
        this.tlsClient = tlsClient
        this.datagramTransport = datagramTransport
        return executor.submit(
            Callable<DTLSTransport> { dtlsClientProtocol.connect(this.tlsClient, this.datagramTransport) }
        )
    }
}

