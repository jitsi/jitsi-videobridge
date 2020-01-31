/*
 * Copyright @ 2018 - present 8x8, Inc.
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
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.utils.logging2.cerror
import org.jitsi.utils.logging2.cinfo
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.logging2.Logger

class DtlsServer(
    private val datagramTransport: DatagramTransport,
    certificateInfo: CertificateInfo,
    private val handshakeCompleteHandler: (Int, TlsRole, ByteArray) -> Unit = { _, _, _ -> },
    verifyAndValidateRemoteCertificate: (Certificate?) -> Unit = {},
    parentLogger: Logger,
    private val dtlsServerProtocol: DTLSServerProtocol = DTLSServerProtocol()
) : DtlsRole {
    private val logger = createChildLogger(parentLogger)

    private val tlsServer: TlsServerImpl = TlsServerImpl(certificateInfo, verifyAndValidateRemoteCertificate, logger)

    override fun start(): DTLSTransport = accept()

    fun accept(): DTLSTransport {
        try {
            return dtlsServerProtocol.accept(tlsServer, datagramTransport).also {
                logger.cinfo { "DTLS handshake finished" }
                handshakeCompleteHandler(
                    tlsServer.chosenSrtpProtectionProfile, TlsRole.SERVER, tlsServer.srtpKeyingMaterial)
            }
        } catch (t: Throwable) {
            logger.cerror { "Error during DTLS connection: $t" }
            throw t
        }
    }
}
