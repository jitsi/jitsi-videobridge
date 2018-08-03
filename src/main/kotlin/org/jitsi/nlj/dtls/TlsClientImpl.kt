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

import org.bouncycastle.crypto.tls.Certificate
import org.bouncycastle.crypto.tls.CertificateRequest
import org.bouncycastle.crypto.tls.DefaultTlsClient
import org.bouncycastle.crypto.tls.DefaultTlsSignerCredentials
import org.bouncycastle.crypto.tls.HashAlgorithm
import org.bouncycastle.crypto.tls.ProtocolVersion
import org.bouncycastle.crypto.tls.SRTPProtectionProfile
import org.bouncycastle.crypto.tls.SignatureAlgorithm
import org.bouncycastle.crypto.tls.SignatureAndHashAlgorithm
import org.bouncycastle.crypto.tls.TlsAuthentication
import org.bouncycastle.crypto.tls.TlsCredentials
import org.bouncycastle.crypto.tls.TlsSRTPUtils
import org.bouncycastle.crypto.tls.TlsUtils
import org.bouncycastle.crypto.tls.UseSRTPData
import java.util.*

/**
 * Implementation of [DefaultTlsClient].
 */
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
                // TODO: Need access to the remote fingerprints here to verify
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
