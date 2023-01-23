/*
 * Copyright @ 2023 - present 8x8, Inc.
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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.bouncycastle.tls.CipherSuite
import org.jitsi.config.withNewConfig
import org.jitsi.metaconfig.ConfigException

class DtlsConfigTest : ShouldSpec() {
    init {
        context("Valid cipher suites") {
            withNewConfig(
                """
                jmt.dtls.cipher-suites = [
                   TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                   TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                ]
                """.trimIndent()
            ) {
                DtlsConfig.config.cipherSuites shouldBe listOf(
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                )
            }
        }
        context("Invalid cipher suites") {
            context("Invalid name") {
                withNewConfig(
                    """
                jmt.dtls.cipher-suites = [
                    TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    invalid
                ]
                    """.trimIndent()
                ) {
                    shouldThrow<ConfigException> { DtlsConfig.config.cipherSuites }
                }
            }

            context("Empty") {
                withNewConfig("jmt.dtls.cipher-suites = []") {
                    shouldThrow<ConfigException> { DtlsConfig.config.cipherSuites }
                }
            }
            context("Wrong type") {
                withNewConfig("jmt.dtls.cipher-suites = 42") {
                    shouldThrow<ConfigException> { DtlsConfig.config.cipherSuites }
                }
            }
        }
    }
}
