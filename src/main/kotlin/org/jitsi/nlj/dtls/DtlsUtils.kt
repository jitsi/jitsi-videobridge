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

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.time.Duration
import java.util.Date
import java.util.NoSuchElementException
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcDefaultDigestProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.TlsContext
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.crypto.TlsSecret
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.cerror
import org.jitsi.utils.logging2.cinfo

val SECURE_RANDOM = SecureRandom()
val BC_TLS_CRYPTO = BcTlsCrypto(SECURE_RANDOM)

/**
 * Various helper utilities for DTLS
 *
 * https://tools.ietf.org/html/draft-ietf-rtcweb-security-arch-18
 */
class DtlsUtils {
    companion object {
        init {
            Security.addProvider(BouncyCastleProvider())
        }

        val config = DtlsConfig()

        fun generateCertificateInfo(): CertificateInfo {
            val cn = generateCN("TODO-APP-NAME", "TODO-APP-VERSION")
            val keyPair = generateEcKeyPair()
            val x509certificate = generateCertificate(cn, keyPair)
            val localFingerprintHashFunction = x509certificate.getHashFunction()
            val localFingerprint = x509certificate.getFingerprint(localFingerprintHashFunction)

            val certificate = org.bouncycastle.tls.Certificate(
                arrayOf(BcTlsCertificate(BC_TLS_CRYPTO, x509certificate))
            )
            return CertificateInfo(
                keyPair,
                certificate,
                localFingerprintHashFunction,
                localFingerprint,
                System.currentTimeMillis()
            )
        }

        /**
         * A helper which finds an SRTP protection profile present in both
         * [ours] and [theirs].  Throws [DtlsException] if no common profile is found.
         */
        fun chooseSrtpProtectionProfile(ours: Iterable<Int>, theirs: Iterable<Int>): Int {
            return try {
                ours.first(theirs::contains)
            } catch (e: NoSuchElementException) {
                throw DtlsException(
                    "No common SRTP protection profile found.  Ours: ${ours.joinToString()} " +
                        "Theirs: ${theirs.joinToString()}"
                )
            }
        }

        /**
         * Generate an x509 certificate valid from 1 day ago until 7 days from now.
         *
         * TODO: make the algorithm dynamic (passed in) to support older dtls versions/clients
         */
        private fun generateCertificate(
            subject: X500Name,
            keyPair: KeyPair
        ): Certificate {
            val now = System.currentTimeMillis()
            val startDate = Date(now - Duration.ofDays(1).toMillis())
            val expiryDate = Date(now + Duration.ofDays(7).toMillis())
            val serialNumber = BigInteger.valueOf(now)

            val certBuilder = JcaX509v3CertificateBuilder(
                subject,
                serialNumber,
                startDate,
                expiryDate,
                subject,
                keyPair.public
            )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)

            return certBuilder.build(signer).toASN1Structure()
        }

        /**
         * Generate an eliptic-curve keypair using the secp256r1 named curve:
         * "All Implementations MUST implement DTLS 1.2 with the
         * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 cipher suite and the P-256
         * curve"
         *
         * --https://tools.ietf.org/html/draft-ietf-rtcweb-security-arch-18#section-6.5
         *
         * NOTE(brian): I used 'secp256r1' specifically because it's what I saw in wireshark traces from chrome
         */
        private fun generateEcKeyPair(): KeyPair {
            val keyGen = KeyPairGenerator.getInstance("EC", "BC")
            val ecCurveSpec = ECNamedCurveTable.getParameterSpec("secp256r1")

            keyGen.initialize(ecCurveSpec)

            return keyGen.generateKeyPair()
        }

        /**
         * Generate an [X500Name] using the given [appName] and [appVersion]
         */
        private fun generateCN(appName: String, appVersion: String): X500Name {
            val builder = X500NameBuilder(BCStyle.INSTANCE)
            val rdn = "$appName $appVersion"
            builder.addRDN(BCStyle.CN, rdn)
            return builder.build()
        }

        /**
         * Verifies and validates a specific certificate against the fingerprints
         * presented by the remote endpoint via the signaling path.
         *
         * @param certificateInfo the certificate to be verified and validated against
         * the fingerprints presented by the remote endpoint via the signaling path
         * @throws [DtlsException] if [certificateInfo] fails validation
         */
        fun verifyAndValidateCertificate(
            certificateInfo: org.bouncycastle.tls.Certificate,
            remoteFingerprints: Map<String, String>
        ) {

            if (certificateInfo.certificateList.isEmpty()) {
                throw DtlsException("No remote fingerprints.")
            }
            for (currCertificate in certificateInfo.certificateList) {
                val x509Cert = Certificate.getInstance(currCertificate.encoded)
                verifyAndValidateCertificate(x509Cert, remoteFingerprints)
            }
        }

        /**
         * Verifies and validates a specific certificate against the fingerprints
         * presented by the remote endpoint via the signaling path.
         *
         * @param certificate the certificate to be verified and validated against
         * the fingerprints presented by the remote endpoint via the signaling path.
         * @throws DtlsException if the specified [certificate] failed to verify
         * and validate against the fingerprints presented by the remote endpoint
         * via the signaling path.
         */
        private fun verifyAndValidateCertificate(
            certificate: Certificate,
            remoteFingerprints: Map<String, String>
        ) {
            // RFC 4572 "Connection-Oriented Media Transport over the Transport
            // Layer Security (TLS) Protocol in the Session Description Protocol
            // (SDP)" defines that "[a] certificate fingerprint MUST be computed
            // using the same one-way hash function as is used in the certificate's
            // signature algorithm."

            val hashFunction = certificate.getHashFunction()

            // As RFC 5763 "Framework for Establishing a Secure Real-time Transport
            // Protocol (SRTP) Security Context Using Datagram Transport Layer
            // Security (DTLS)" states, "the certificate presented during the DTLS
            // handshake MUST match the fingerprint exchanged via the signaling path
            // in the SDP."
            val remoteFingerprint = remoteFingerprints[hashFunction] ?: throw DtlsException(
                "No fingerprint declared over the signaling path with hash function: $hashFunction"
            )

            // TODO(boris) check if the below is still true, and re-introduce the hack if it is.
            // Unfortunately, Firefox does not comply with RFC 5763 at the time
            // of this writing. Its certificate uses SHA-1 and it sends a
            // fingerprint computed with SHA-256. We could, of course, wait for
            // Mozilla to make Firefox compliant. However, we would like to
            // support Firefox in the meantime. That is why we will allow the
            // fingerprint to "upgrade" the hash function of the certificate
            // much like SHA-256 is an "upgrade" of SHA-1.
            /*
            if (remoteFingerprint == null)
            {
                val hashFunctionUpgrade = findHashFunctionUpgrade(hashFunction, remoteFingerprints)

                if (hashFunctionUpgrade != null
                        && !hashFunctionUpgrade.equalsIgnoreCase(hashFunction)) {
                    fingerprint = fingerprints[hashFunctionUpgrade]
                    if (fingerprint != null)
                        hashFunction = hashFunctionUpgrade
                }
            }
            */

            val certificateFingerprint = certificate.getFingerprint(hashFunction)

            if (remoteFingerprint != certificateFingerprint) {
                throw DtlsException(
                    "Fingerprint $remoteFingerprint does not match the $hashFunction-hashed " +
                        "certificate $certificateFingerprint"
                )
            }
        }

        /**
         * Determine and return the hash function (as a [String]) used by this certificate
         */
        private fun Certificate.getHashFunction(): String {
            val digAlgId = DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgorithm)

            return BcDefaultDigestProvider.INSTANCE
                .get(digAlgId)
                .algorithmName
                .lowercase()
        }

        /**
         * Computes the fingerprint of a [org.bouncycastle.asn1.x509.Certificate] using [hashFunction] and returns it
         * as a [String]
         */
        private fun Certificate.getFingerprint(hashFunction: String): String {
            val digAlgId = DefaultDigestAlgorithmIdentifierFinder().find(hashFunction.uppercase())
            val digest = BcDefaultDigestProvider.INSTANCE.get(digAlgId)
            val input: ByteArray = getEncoded(ASN1Encoding.DER)
            val output = ByteArray(digest.digestSize)

            digest.update(input, 0, input.size)
            digest.doFinal(output, 0)

            return output.toFingerprint()
        }

        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
        /**
         * Helper function to convert a [ByteArray] to a colon-delimited hex string
         */
        private fun ByteArray.toFingerprint(): String {
            val buf = StringBuffer()
            for (i in 0 until size) {
                val octet = get(i).toInt()
                val firstIndex = (octet and 0xF0).ushr(4)
                val secondIndex = octet and 0x0F
                buf.append(HEX_CHARS[firstIndex])
                buf.append(HEX_CHARS[secondIndex])
                if (i < size - 1) {
                    buf.append(":")
                }
            }
            return buf.toString()
        }

        /*
         * Copied from TlsContext#exportKeyingMaterial and modified to work with
         * an externally provided masterSecret value.
         */
        fun exportKeyingMaterial(
            context: TlsContext,
            asciiLabel: String,
            context_value: ByteArray?,
            length: Int,
            masterSecret: TlsSecret
        ): ByteArray {
            if (context_value != null && !TlsUtils.isValidUint16(context_value.size)) {
                throw IllegalArgumentException("'context_value' must have a length less than 2^16 (or be null)")
            }
            val sp = context.securityParameters
            val cr = sp.clientRandom
            val sr = sp.serverRandom

            var seedLength = cr.size + sr.size
            if (context_value != null) {
                seedLength += (2 + context_value.size)
            }

            val seed = ByteArray(seedLength)
            var seedPos = 0

            System.arraycopy(cr, 0, seed, seedPos, cr.size)
            seedPos += cr.size
            System.arraycopy(sr, 0, seed, seedPos, sr.size)
            seedPos += sr.size

            if (context_value != null) {
                TlsUtils.writeUint16(context_value.size, seed, seedPos)
                seedPos += 2
                System.arraycopy(context_value, 0, seed, seedPos, context_value.size)
                seedPos += context_value.size
            }

            if (seedPos != seedLength) {
                throw IllegalStateException("error in calculation of seed for export")
            }

            return TlsUtils.PRF(context, masterSecret, asciiLabel, seed, length).extract()
        }
    }

    class DtlsException(msg: String) : Exception(msg)
}

@Suppress("NOTHING_TO_INLINE") // Avoid adding to the trace in the log file
inline fun Logger.notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) {
    when (alertDescription) {
        AlertDescription.close_notify -> cdebug { "close_notify raised, connection closing" }
        else -> {
            val stack = with(StringBuffer()) {
                val e = Exception()
                for (el in e.stackTrace) {
                    appendLine(el.toString())
                }
                toString()
            }
            cinfo {
                "Alert raised: level=$alertLevel, description=$alertDescription, message=$message " +
                    "cause=$cause $stack"
            }
        }
    }
}

@Suppress("NOTHING_TO_INLINE") // Avoid adding to the trace in the log file
inline fun Logger.notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
    when (alertDescription) {
        AlertDescription.close_notify -> cinfo { "close_notify received, connection closing" }
        else -> cerror {
            "Alert received: level=$alertLevel, description=$alertDescription " +
                "(${AlertDescription.getName(alertDescription)})"
        }
    }
}
