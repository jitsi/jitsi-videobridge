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

import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.ClientCertificateType
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ExporterLabel
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.NamedGroup
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedDecryptor
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsCredentials
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
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cinfo
import org.jitsi.utils.logging2.createChildLogger
import java.nio.ByteBuffer
import java.util.Hashtable
import java.util.Vector

class TlsServerImpl(
    private val certificateInfo: CertificateInfo,
    /**
     * The function to call when the client certificateInfo is available.
     */
    private val notifyClientCertificateReceived: (Certificate?) -> Unit,
    parentLogger: Logger
) : DefaultTlsServer(BC_TLS_CRYPTO) {

    private val logger = createChildLogger(parentLogger)

    private var session: TlsSession? = null

    /**
     * Only set after a handshake has completed
     */
    lateinit var srtpKeyingMaterial: ByteArray
        private set

    var chosenSrtpProtectionProfile: Int = 0

    /**
     * The DTLS protocol version negotiated with the client. Only set after a handshake has completed.
     */
    var negotiatedProtocolVersion: ProtocolVersion? = null
        private set

    /**
     * The key exchange group ([NamedGroup]) negotiated with the client. Only set after a (D)TLS 1.3 handshake has
     * completed (it is not recorded for DTLS 1.2).
     */
    var negotiatedGroup: Int? = null
        private set

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

    override fun getCipherSuites() = DtlsConfig.config.cipherSuites.toIntArray()

    /**
     * The key exchange groups we support, in order of preference: the post-quantum hybrid (if enabled) first.
     * Only used for DTLS 1.3 key_share selection; for DTLS 1.2 the base class picks a curve from the client's list.
     */
    override fun getSupportedGroups(): IntArray {
        val groups = super.getSupportedGroups()
        return if (DtlsConfig.config.offerPostQuantumKeyExchange) {
            intArrayOf(NamedGroup.X25519MLKEM768) + groups
        } else {
            groups
        }
    }

    /**
     * Select the key exchange group by our preference order rather than the client's, so that the post-quantum
     * hybrid is used whenever the client supports it, wherever the client happens to list it.
     */
    override fun preferLocalSupportedGroups(): Boolean = DtlsConfig.config.offerPostQuantumKeyExchange

    override fun getRSAEncryptionCredentials(): TlsCredentialedDecryptor {
        return BcDefaultTlsCredentialedDecryptor(
            (context.crypto as BcTlsCrypto),
            certificateInfo.certificateFor(context),
            PrivateKeyFactory.createKey(certificateInfo.keyPair.private.encoded)
        )
    }

    /**
     * In (D)TLS 1.3 there is no key exchange algorithm to select the credentials by (the base implementation
     * throws for the NULL key exchange), and the server always authenticates with a signature. We only have an
     * ECDSA certificate, so always use it.
     */
    override fun getCredentials(): TlsCredentials = if (TlsUtils.isTLSv13(context)) {
        getECDSASignerCredentials()
    } else {
        super.getCredentials()
    }

    override fun getECDSASignerCredentials(): TlsCredentialedSigner {
        return BcDefaultTlsCredentialedSigner(
            TlsCryptoParameters(context),
            (context.crypto as BcTlsCrypto),
            PrivateKeyFactory.createKey(certificateInfo.keyPair.private.encoded),
            certificateInfo.certificateFor(context),
            SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
        )
    }

    override fun getCertificateRequest(): CertificateRequest {
        val signatureAlgorithms = Vector<SignatureAndHashAlgorithm>(1)
        signatureAlgorithms.add(SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa))
        return if (TlsUtils.isTLSv13(context)) {
            // RFC 8446 4.3.2: the certificate_request_context is empty during the handshake.
            CertificateRequest(TlsUtils.EMPTY_BYTES, signatureAlgorithms, null, null)
        } else {
            CertificateRequest(shortArrayOf(ClientCertificateType.ecdsa_sign), signatureAlgorithms, null)
        }
    }

    override fun getHandshakeTimeoutMillis(): Int = DtlsConfig.config.handshakeTimeout.toMillis().toInt()

    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete()
        negotiatedProtocolVersion = context.securityParameters.negotiatedVersion
        // Only recorded for (D)TLS 1.3 key shares; -1 otherwise.
        negotiatedGroup = context.securityParameters.negotiatedGroup.takeIf { it >= 0 }
        logger.cinfo {
            "Negotiated DTLS version $negotiatedProtocolVersion" +
                (negotiatedGroup?.let { ", key exchange group ${NamedGroup.getText(it)}" } ?: "")
        }
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
        // (D)TLS 1.3 always uses the exporter master secret; the fallback below only applies to (D)TLS 1.2
        // sessions negotiated without extended_master_secret.
        if (!TlsUtils.isTLSv13(context) && !context.securityParameters.isExtendedMasterSecret) {
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

    override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) =
        logger.notifyAlertRaised(alertLevel, alertDescription, message, cause)

    override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) =
        logger.notifyAlertReceived(alertLevel, alertDescription)

    override fun getSupportedVersions(): Array<ProtocolVersion> = DtlsConfig.config.supportedVersions
}
