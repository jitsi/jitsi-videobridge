/*
 * Copyright @ 2019 - present 8x8, Inc.
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

import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.ProtocolVersion
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.config
import java.time.Duration

class DtlsConfig private constructor() {
    val handshakeTimeout: Duration by config {
        "jmt.dtls.handshake-timeout".from(JitsiConfig.newConfig)
    }

    val cipherSuites: List<Int> by config {
        "jmt.dtls.cipher-suites".from(JitsiConfig.newConfig).convertFrom<List<String>> { list ->
            val ciphers = list.map { it.toBcCipherSuite() }
            if (ciphers.isEmpty()) {
                throw ConfigException.UnableToRetrieve.ConditionNotMet("cipher-suites must not be empty")
            }
            ciphers
        }
    }

    /**
     * Whether DTLS 1.3 is offered (as a client) and accepted (as a server). DTLS 1.2 is always supported.
     */
    val dtls13Enabled: Boolean by config {
        "jmt.dtls.enable-dtls13".from(JitsiConfig.newConfig)
    }

    /**
     * The DTLS protocol versions to offer/accept, in decreasing order of preference.
     */
    val supportedVersions: Array<ProtocolVersion>
        get() = if (dtls13Enabled) {
            ProtocolVersion.DTLSv13.downTo(ProtocolVersion.DTLSv12)
        } else {
            ProtocolVersion.DTLSv12.only()
        }

    /**
     * Whether to offer/select the X25519MLKEM768 post-quantum hybrid key exchange (DTLS 1.3 only).
     */
    val postQuantumKeyExchangeEnabled: Boolean by config {
        "jmt.dtls.enable-post-quantum-key-exchange".from(JitsiConfig.newConfig)
    }

    /**
     * Whether to actually offer/select the post-quantum hybrid key exchange. It can only be negotiated with
     * DTLS 1.3, so there is no point offering it when DTLS 1.3 is disabled.
     */
    val offerPostQuantumKeyExchange: Boolean
        get() = dtls13Enabled && postQuantumKeyExchangeEnabled

    val localFingerprintHashFunction: String by config {
        "jmt.dtls.local-fingerprint-hash-function".from(JitsiConfig.newConfig).transformedBy {
            validateHashFunction(it)
        }
    }

    val acceptedFingerprintHashFunctions: List<String> by config {
        "jmt.dtls.accepted-fingerprint-hash-functions".from(JitsiConfig.newConfig).convertFrom<List<String>> { list ->
            if (list.isEmpty()) {
                throw ConfigException.UnableToRetrieve.ConditionNotMet(
                    "accepted-fingerprint-hash-functions must not be empty"
                )
            }
            list.map { validateHashFunction(it) }
        }
    }

    /**
     * Whether to leave the local certificate unsigned (RFC 9925) rather than self-signing it.
     */
    val useAlgUnsigned: Boolean by config {
        "jmt.dtls.use-alg-unsigned".from(JitsiConfig.newConfig)
    }

    companion object {
        val config = DtlsConfig()
    }
}

private fun validateHashFunction(func: String): String {
    val ucFunc = func.uppercase()
    DefaultDigestAlgorithmIdentifierFinder().find(ucFunc)
        ?: throw ConfigException.UnableToRetrieve.WrongType("Unknown hash function $func")
    if (ucFunc == "MD5" || ucFunc == "MD2") {
        throw ConfigException.UnableToRetrieve.WrongType("Forbidden hash function $func")
    }
    return func.lowercase()
}

private fun String.toBcCipherSuite(): Int = try {
    CipherSuite::class.java.getDeclaredField(this).getInt(null)
} catch (e: Exception) {
    throw ConfigException.UnableToRetrieve.ConditionNotMet("Value is not a valid BouncyCastle cipher suite name: $this")
}
