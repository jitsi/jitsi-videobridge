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

import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ExporterLabel
import org.bouncycastle.tls.ExtensionType
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.NamedGroup
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsSession
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.UseSRTPData
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.jitsi.nlj.srtp.SrtpConfig
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.cinfo
import org.jitsi.utils.logging2.createChildLogger
import java.nio.ByteBuffer
import java.util.Hashtable
import java.util.Vector

/**
 * Implementation of [DefaultTlsClient].
 */
class TlsClientImpl(
    private val certificateInfo: CertificateInfo,
    /**
     * The function to call when the server certificateInfo is available.
     */
    private val notifyServerCertificate: (Certificate?) -> Unit,
    parentLogger: Logger
) : DefaultTlsClient(BC_TLS_CRYPTO) {

    private val logger = createChildLogger(parentLogger)

    private var session: TlsSession? = null

    private var clientCredentials: TlsCredentials? = null

    /**
     * Only set after a handshake has completed
     */
    lateinit var srtpKeyingMaterial: ByteArray
        private set

    var chosenSrtpProtectionProfile: Int = 0

    /**
     * The DTLS protocol version negotiated with the server. Only set after a handshake has completed. Volatile
     * because it is written on the handshake thread and read for the debug state.
     */
    @Volatile
    var negotiatedProtocolVersion: ProtocolVersion? = null
        private set

    /**
     * The key exchange group ([NamedGroup]) negotiated with the server. Only set after a (D)TLS 1.3 handshake has
     * completed (BouncyCastle does not record it for DTLS 1.2, where it is -1).
     */
    @Volatile
    var negotiatedGroup: Int? = null
        private set

    override fun getSessionToResume(): TlsSession? = session

    override fun getAuthentication(): TlsAuthentication {
        return object : TlsAuthentication {
            override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials {
                // NOTE: can't set clientCredentials when it is declared because 'context' won't be set yet
                if (clientCredentials == null) {
                    clientCredentials = BcDefaultTlsCredentialedSigner(
                        TlsCryptoParameters(context),
                        (context.crypto as BcTlsCrypto),
                        PrivateKeyFactory.createKey(certificateInfo.keyPair.private.encoded),
                        certificateInfo.certificateFor(context, certificateRequest.certificateRequestContext),
                        if (TlsUtils.isSignatureAlgorithmsExtensionAllowed(context.serverVersion)) {
                            SignatureAndHashAlgorithm(
                                HashAlgorithm.sha256,
                                SignatureAlgorithm.ecdsa
                            )
                        } else {
                            null
                        }
                    )
                }
                return clientCredentials!!
            }

            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                this@TlsClientImpl.notifyServerCertificate(serverCertificate.certificate)
            }
        }
    }

    override fun getClientExtensions(): Hashtable<*, *> {
        var clientExtensions = super.getClientExtensions()
        if (TlsSRTPUtils.getUseSRTPExtension(clientExtensions) == null) {
            if (clientExtensions == null) {
                clientExtensions = Hashtable<Int, ByteArray>()
            }

            TlsSRTPUtils.addUseSRTPExtension(
                clientExtensions,
                UseSRTPData(SrtpConfig.protectionProfiles.toIntArray(), TlsUtils.EMPTY_BYTES)
            )
        }
        clientExtensions.put(ExtensionType.renegotiation_info, byteArrayOf(0))

        return clientExtensions
    }

    override fun processServerExtensions(serverExtensions: Hashtable<*, *>?) {
        // TODO: a few cases we should be throwing alerts for in here.  see old TlsClientImpl
        // Failures are raised as TlsFatalAlerts: BouncyCastle does not handle the checked DtlsException, and the
        // handshake would be abandoned without an alert to the server.
        val useSRTPData = TlsSRTPUtils.getUseSRTPExtension(serverExtensions)
            ?: throw TlsFatalAlert(AlertDescription.handshake_failure, "No use_srtp extension")
        val protectionProfiles = useSRTPData.protectionProfiles
        chosenSrtpProtectionProfile = try {
            DtlsUtils.chooseSrtpProtectionProfile(SrtpConfig.protectionProfiles, protectionProfiles.asIterable())
        } catch (e: DtlsUtils.DtlsException) {
            throw TlsFatalAlert(AlertDescription.handshake_failure, e.message, e)
        }
    }

    override fun getCipherSuites() = DtlsConfig.config.cipherSuites.toIntArray()

    /**
     * Offer the post-quantum hybrid group first, if enabled. It can only be negotiated with DTLS 1.3; a DTLS 1.2
     * server will just ignore it and pick one of the classical groups that follow. BouncyCastle's default
     * [getEarlyKeyShareGroups] sends a key_share for the hybrid whenever it is offered, alongside one for X25519, so
     * a DTLS 1.3 server without the hybrid needs no HelloRetryRequest round trip.
     */
    override fun getSupportedGroups(namedGroupRoles: Vector<*>?): Vector<Int> {
        @Suppress("UNCHECKED_CAST")
        val groups = super.getSupportedGroups(namedGroupRoles) as Vector<Int>
        if (DtlsConfig.config.offerPostQuantumKeyExchange) {
            groups.insertElementAt(NamedGroup.X25519MLKEM768, 0)
        }
        return groups
    }

    override fun getHandshakeTimeoutMillis(): Int = DtlsConfig.config.handshakeTimeout.toMillis().toInt()

    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete()
        negotiatedProtocolVersion = context.securityParameters.negotiatedVersion
        negotiatedGroup = context.securityParameters.negotiatedGroup.takeIf { it >= 0 }
        logger.cinfo {
            "Negotiated DTLS version $negotiatedProtocolVersion" +
                (negotiatedGroup?.let { ", key exchange group ${NamedGroup.getText(it)}" } ?: "")
        }
        context.resumableSession?.let { newSession ->

            session?.let { existingSession ->
                if (existingSession.sessionID?.contentEquals(newSession.sessionID) == true) {
                    logger.cdebug {
                        val newSessionIdHex = ByteBuffer.wrap(newSession.sessionID).toHex()
                        "Resumed DTLS session $newSessionIdHex"
                    }
                }
            } ?: run {
                logger.cdebug {
                    val newSessionIdHex = ByteBuffer.wrap(newSession.sessionID).toHex()
                    "Established DTLS session $newSessionIdHex"
                }
                this.session = newSession
            }
        }
        val srtpProfileInformation =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        srtpKeyingMaterial = context.exportKeyingMaterial(
            ExporterLabel.dtls_srtp,
            null,
            2 * (srtpProfileInformation.cipherKeyLength + srtpProfileInformation.cipherSaltLength)
        )
    }

    override fun getSupportedVersions(): Array<ProtocolVersion> = DtlsConfig.config.supportedVersions

    override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) =
        logger.notifyAlertRaised(alertLevel, alertDescription, message, cause)

    override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) =
        logger.notifyAlertReceived(alertLevel, alertDescription)
}
