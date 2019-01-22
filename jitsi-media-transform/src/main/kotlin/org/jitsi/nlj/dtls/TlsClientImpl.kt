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
import org.bouncycastle.crypto.tls.TlsContext
import org.bouncycastle.crypto.tls.TlsCredentials
import org.bouncycastle.crypto.tls.TlsSRTPUtils
import org.bouncycastle.crypto.tls.TlsUtils
import org.bouncycastle.crypto.tls.UseSRTPData
import org.jitsi.nlj.util.getLogger
import java.util.*

/**
 * Implementation of [DefaultTlsClient].
 */
class TlsClientImpl(
        /**
         * The function to call when the server certificate is available.
         */
    private val notifyServerCertificate: (Certificate) -> Unit
) : DefaultTlsClient() {

    private val logger = getLogger(this.javaClass)

    private var clientCredentials: TlsCredentials? = null

    /**
     * The SRTP Master Key Identifier (MKI) used by the
     * <tt>SRTPCryptoContext</tt> associated with this instance. Since the
     * <tt>SRTPCryptoContext</tt> class does not utilize it, the value is
     * {@link TlsUtils#EMPTY_BYTES}.
     */
    private val mki = TlsUtils.EMPTY_BYTES

    private val srtpProtectionProfiles = intArrayOf(
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32
    )

    var chosenSrtpProtectionProfile: Int = 0

    fun getContext(): TlsContext = context

    override fun getAuthentication(): TlsAuthentication {
        return object : TlsAuthentication {
            override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials {
                // TODO: Do this via some sort of observer instead? Does this need to be updated it if survives across
                // a certificate expiration?
                // NOTE: can't just assign this outright because 'context' won't be set yet
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

            /**
             * (?) BouncyCastle calls this when the DTLS connection is established.
             */
            override fun notifyServerCertificate(certificate: Certificate) {
                this@TlsClientImpl.notifyServerCertificate(certificate)
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
                UseSRTPData(srtpProtectionProfiles, mki)
            )
        }

        return clientExtensions
    }

    override fun processServerExtensions(serverExtensions: Hashtable<*, *>?) {
        //TODO: a few cases we should be throwing alerts for in here.  see old TlsClientImpl
        val useSRTPData = TlsSRTPUtils.getUseSRTPExtension(serverExtensions);
        val protectionProfiles = useSRTPData.protectionProfiles;
        chosenSrtpProtectionProfile = when (protectionProfiles.size) {
            1 -> DtlsUtils.chooseSrtpProtectionProfile(srtpProtectionProfiles, protectionProfiles)
            else -> 0
        }
        if (chosenSrtpProtectionProfile == 0) {
            // throw alert
        }
    }

    override fun notifyHandshakeComplete() {
        //TODO: as of (at least) v 1.60 of BC, extracting the key information
        // after dtlisclientprotocol.connect finishes will not work, as the
        // connect method clears the security parameters at the end.  according to the
        // gh link below, this method is the hook that should be used to export the keying material
        // in later versions (also, the tls.crypto api is deprecated and we should move off of that)
        // https://github.com/bcgit/bc-java/issues/203
        // "The intention is that you call exportKeyingMaterial during the notifyHandshakeComplete
        // callback on your TlsClient subclass"
        //context.exportKeyingMaterial()
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
        logger.info(stack)
    }

    override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
    }
}
