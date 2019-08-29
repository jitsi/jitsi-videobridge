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
package org.jitsi.nlj.srtp

import org.bouncycastle.crypto.tls.ExporterLabel
import org.bouncycastle.crypto.tls.SRTPProtectionProfile
import org.bouncycastle.crypto.tls.TlsClientContext
import org.bouncycastle.crypto.tls.TlsContext
import org.bouncycastle.crypto.tls.TlsServerContext
import org.jitsi.srtp.SrtpContextFactory
import org.jitsi.srtp.SrtpPolicy

enum class TlsRole {
    CLIENT,
    SERVER;

    companion object {
        fun fromTlsContext(tlsContext: TlsContext): TlsRole {
            return when (tlsContext) {
                is TlsClientContext -> CLIENT
                is TlsServerContext -> SERVER
                else -> throw Exception("Unsupported tls role: ${tlsContext::class}")
            }
        }
    }
}

class SrtpUtil {
    companion object {
        fun getSrtpProfileInformationFromSrtpProtectionProfile(srtpProtectionProfile: Int): SrtpProfileInformation {
            return when (srtpProtectionProfile) {
                SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32 -> {
                    SrtpProfileInformation(
                        cipherKeyLength = 128 / 8,
                        cipherSaltLength = 112 / 8,
                        cipherName = SrtpPolicy.AESCM_ENCRYPTION,
                        authFunctionName = SrtpPolicy.HMACSHA1_AUTHENTICATION,
                        authKeyLength = 160 / 8,
                        rtcpAuthTagLength = 80 / 8,
                        rtpAuthTagLength = 32 / 8
                    )
                }
                SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80 -> {
                    SrtpProfileInformation(
                        cipherKeyLength = 128 / 8,
                        cipherSaltLength = 112 / 8,
                        cipherName = SrtpPolicy.AESCM_ENCRYPTION,
                        authFunctionName = SrtpPolicy.HMACSHA1_AUTHENTICATION,
                        authKeyLength = 160 / 8,
                        rtcpAuthTagLength = 80 / 8,
                        rtpAuthTagLength = 80 / 8
                    )
                }
                SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_32 -> {
                    SrtpProfileInformation(
                        cipherKeyLength = 0,
                        cipherSaltLength = 0,
                        cipherName = SrtpPolicy.NULL_ENCRYPTION,
                        authFunctionName = SrtpPolicy.HMACSHA1_AUTHENTICATION,
                        authKeyLength = 160 / 8,
                        rtcpAuthTagLength = 80 / 8,
                        rtpAuthTagLength = 32 / 8
                    )
                }
                SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_80 -> {
                    SrtpProfileInformation(
                        cipherKeyLength = 0,
                        cipherSaltLength = 0,
                        cipherName = SrtpPolicy.NULL_ENCRYPTION,
                        authFunctionName = SrtpPolicy.HMACSHA1_AUTHENTICATION,
                        authKeyLength = 160 / 8,
                        rtcpAuthTagLength = 80 / 8,
                        rtpAuthTagLength = 80 / 8
                    )
                }
                else -> throw IllegalArgumentException("Unsupported SRTP protection profile: $srtpProtectionProfile")
            }
        }

        fun getKeyingMaterial(tlsContext: TlsContext, srtpProfileInformation: SrtpProfileInformation): ByteArray {
            return tlsContext.exportKeyingMaterial(
                ExporterLabel.dtls_srtp,
                null,
                2 * (srtpProfileInformation.cipherKeyLength + srtpProfileInformation.cipherSaltLength)
            )
        }

        fun initializeTransformer(
            srtpProfileInformation: SrtpProfileInformation,
            keyingMaterial: ByteArray,
            tlsRole: TlsRole
        ): SrtpTransformers {
            val clientWriteSrtpMasterKey = ByteArray(srtpProfileInformation.cipherKeyLength)
            val serverWriteSrtpMasterKey = ByteArray(srtpProfileInformation.cipherKeyLength)
            val clientWriterSrtpMasterSalt = ByteArray(srtpProfileInformation.cipherSaltLength)
            val serverWriterSrtpMasterSalt = ByteArray(srtpProfileInformation.cipherSaltLength)
            val keyingMaterialValues = listOf(
                clientWriteSrtpMasterKey,
                serverWriteSrtpMasterKey,
                clientWriterSrtpMasterSalt,
                serverWriterSrtpMasterSalt
            )

            var keyingMaterialOffset = 0
            for (i in 0 until keyingMaterialValues.size) {
                val keyingMaterialValue = keyingMaterialValues[i]

                System.arraycopy(keyingMaterial, keyingMaterialOffset,
                    keyingMaterialValue, 0,
                    keyingMaterialValue.size)
                keyingMaterialOffset += keyingMaterialValue.size
            }

            val srtcpPolicy = SrtpPolicy(
                srtpProfileInformation.cipherName,
                srtpProfileInformation.cipherKeyLength,
                srtpProfileInformation.authFunctionName,
                srtpProfileInformation.authKeyLength,
                srtpProfileInformation.rtcpAuthTagLength,
                srtpProfileInformation.cipherSaltLength
            )
            val srtpPolicy = SrtpPolicy(
                srtpProfileInformation.cipherName,
                srtpProfileInformation.cipherKeyLength,
                srtpProfileInformation.authFunctionName,
                srtpProfileInformation.authKeyLength,
                srtpProfileInformation.rtpAuthTagLength,
                srtpProfileInformation.cipherSaltLength
            )

            /* To support RetransmissionSender.retransmitPlain, we need to allow send-side SRTP replay. */
            /* TODO: enable this only in cases where we actually need to use retransmitPlain? */
            srtpPolicy.isSendReplayEnabled = true

            val clientSrtpContextFactory = SrtpContextFactory(
                tlsRole == TlsRole.CLIENT,
                clientWriteSrtpMasterKey,
                clientWriterSrtpMasterSalt,
                srtpPolicy,
                srtcpPolicy
            )
            val serverSrtpContextFactory = SrtpContextFactory(
                tlsRole == TlsRole.SERVER,
                serverWriteSrtpMasterKey,
                serverWriterSrtpMasterSalt,
                srtpPolicy,
                srtcpPolicy
            )
            val forwardSrtpContextFactory: SrtpContextFactory
            val reverseSrtpContextFactory: SrtpContextFactory

            when (tlsRole) {
                TlsRole.CLIENT -> {
                    forwardSrtpContextFactory = clientSrtpContextFactory
                    reverseSrtpContextFactory = serverSrtpContextFactory
                }
                TlsRole.SERVER -> {
                    forwardSrtpContextFactory = serverSrtpContextFactory
                    reverseSrtpContextFactory = clientSrtpContextFactory
                }
            }

            return SrtpTransformers(
                SrtpDecryptTransformer(reverseSrtpContextFactory),
                SrtpEncryptTransformer(forwardSrtpContextFactory),
                SrtcpDecryptTransformer(reverseSrtpContextFactory),
                SrtcpEncryptTransformer(forwardSrtpContextFactory)
            )
        }
    }
}
