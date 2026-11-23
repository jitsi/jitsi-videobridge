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

import org.bouncycastle.tls.CipherSuite
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

    companion object {
        val config = DtlsConfig()
    }
}

/**
 * The hash function names of the "Hash Function Textual Names" IANA registry which RFC 8122 allows for fingerprints
 * and which we support. These are the names used in signaling, so they are the only ones that can ever match a
 * remote fingerprint; a spelling BouncyCastle would also understand, such as "sha256", would silently never match.
 * MD2 and MD5 are in the registry but RFC 8122 section 5 forbids them; shake128 and shake256 (RFC 8692) are in the
 * registry but not supported here.
 */
private val fingerprintHashFunctions = setOf("sha-1", "sha-224", "sha-256", "sha-384", "sha-512")

private fun validateHashFunction(func: String): String {
    val lcFunc = func.lowercase()
    if (lcFunc == "md5" || lcFunc == "md2") {
        throw ConfigException.UnableToRetrieve.WrongType("Forbidden hash function $func")
    }
    if (lcFunc !in fingerprintHashFunctions) {
        throw ConfigException.UnableToRetrieve.WrongType(
            "Unknown hash function $func, not one of ${fingerprintHashFunctions.joinToString()}"
        )
    }
    return lcFunc
}

private fun String.toBcCipherSuite(): Int = try {
    CipherSuite::class.java.getDeclaredField(this).getInt(null)
} catch (e: Exception) {
    throw ConfigException.UnableToRetrieve.ConditionNotMet("Value is not a valid BouncyCastle cipher suite name: $this")
}
