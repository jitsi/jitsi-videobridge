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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import java.nio.ByteBuffer
import java.util.Hashtable
import java.util.Vector
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.ClientCertificateType
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ExporterLabel
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedDecryptor
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsSession
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.UseSRTPData
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedDecryptor
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.jitsi.nlj.srtp.SrtpConfig
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.utils.logging2.cinfo
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.Logger

@SuppressFBWarnings(
    value = ["NP_ALWAYS_NULL"],
    justification = "False positives with 'lateinit'."
)
class TlsServerImpl(
    private val certificateInfo: CertificateInfo,
    /**
     * The function to call when the client certificateInfo is available.
     */
    private val notifyClientCertificateReceived: (Certificate?) -> Unit,
    parentLogger: Logger
) : DefaultTlsServer(BC_TLS_CRYPTO) {

    private val logger = createChildLogger(parentLogger)

    private val config = DtlsConfig()

    private var session: TlsSession? = null

    /**
     * Only set after a handshake has completed
     */
    lateinit var srtpKeyingMaterial: ByteArray

    var chosenSrtpProtectionProfile: Int = 0

    override fun getSessionToResume(sessionID: ByteArray?): TlsSession? {
        return session
        // TODO: do we need to map multiple sessions (per sessionID?)
//        return super.getSessionToResume(sessionID)
    }

    override fun getServerExtensions(): Hashtable<*, *> {
        val extensions = super.getServerExtensions()
            ?: Hashtable<Int, ByteArray>()
        return extensions.also {
            if (TlsSRTPUtils.getUseSRTPExtension(it) == null) {
                TlsSRTPUtils.addUseSRTPExtension(
                    it,
                    UseSRTPData(intArrayOf(chosenSrtpProtectionProfile), TlsUtils.EMPTY_BYTES)
                )
            }
        }
    }

    override fun processClientExtensions(clientExtensions: Hashtable<*, *>?) {
        super.processClientExtensions(clientExtensions)
        val useSRTPData = TlsSRTPUtils.getUseSRTPExtension(clientExtensions)
        val protectionProfiles = useSRTPData.protectionProfiles
        chosenSrtpProtectionProfile =
            DtlsUtils.chooseSrtpProtectionProfile(SrtpConfig.protectionProfiles, protectionProfiles.asIterable())
    }

    override fun getCipherSuites(): IntArray {
        return intArrayOf(
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA
        )
    }

    override fun getRSAEncryptionCredentials(): TlsCredentialedDecryptor {
        return BcDefaultTlsCredentialedDecryptor(
            (context.crypto as BcTlsCrypto),
            certificateInfo.certificate,
            PrivateKeyFactory.createKey(certificateInfo.keyPair.private.encoded)
        )
    }

    override fun getECDSASignerCredentials(): TlsCredentialedSigner {
        return BcDefaultTlsCredentialedSigner(
            TlsCryptoParameters(context),
            (context.crypto as BcTlsCrypto),
            PrivateKeyFactory.createKey(certificateInfo.keyPair.private.encoded),
            certificateInfo.certificate,
            SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
        )
    }

    override fun getCertificateRequest(): CertificateRequest {
        val signatureAlgorithms = Vector<SignatureAndHashAlgorithm>(1)
        signatureAlgorithms.add(SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa))
        signatureAlgorithms.add(SignatureAndHashAlgorithm(HashAlgorithm.sha1, SignatureAlgorithm.rsa))
        return when (context.clientVersion) {
            ProtocolVersion.DTLSv10 -> {
                CertificateRequest(
                    shortArrayOf(ClientCertificateType.rsa_sign),
                    null,
                    null
                )
            }
            ProtocolVersion.DTLSv12 -> {
                CertificateRequest(
                    shortArrayOf(ClientCertificateType.ecdsa_sign),
                    signatureAlgorithms,
                    null
                )
            }
            else -> throw DtlsUtils.DtlsException("Unsupported version: ${context.clientVersion}")
        }
    }

    override fun getHandshakeTimeoutMillis(): Int = config.handshakeTimeout.toMillis().toInt()

    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete()
        context.resumableSession?.let { newSession ->
            val newSessionIdHex = ByteBuffer.wrap(newSession.sessionID).toHex()

            session?.let { existingSession ->
                if (existingSession.sessionID?.contentEquals(newSession.sessionID) == true) {
                    logger.cinfo { "Resumed DTLS session $newSessionIdHex" }
                }
            } ?: run {
                logger.cinfo { "Established DTLS session $newSessionIdHex" }
                this.session = newSession
            }
        }
        val srtpProfileInformation =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        if (!context.securityParameters.isExtendedMasterSecret) {
            context.session?.exportSessionParameters()?.masterSecret?.let {
                srtpKeyingMaterial = DtlsUtils.exportKeyingMaterial(
                    context,
                    ExporterLabel.dtls_srtp,
                    null,
                    2 * (srtpProfileInformation.cipherKeyLength + srtpProfileInformation.cipherSaltLength),
                    it
                )
            }
        } else {
            srtpKeyingMaterial = context.exportKeyingMaterial(
                ExporterLabel.dtls_srtp,
                null,
                2 * (srtpProfileInformation.cipherKeyLength + srtpProfileInformation.cipherSaltLength)
            )
        }
    }

    override fun notifyClientCertificate(clientCertificate: Certificate?) {
        notifyClientCertificateReceived(clientCertificate)
    }

    override fun notifyClientVersion(clientVersion: ProtocolVersion?) {
        super.notifyClientVersion(clientVersion)

        logger.cinfo { "Negotiated DTLS version $clientVersion" }
    }

    override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) =
        logger.notifyAlertRaised(alertLevel, alertDescription, message, cause)

    override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) =
        logger.notifyAlertReceived(alertLevel, alertDescription)

    override fun getSupportedVersions(): Array<ProtocolVersion> =
        ProtocolVersion.DTLSv12.downTo(ProtocolVersion.DTLSv10)
}
