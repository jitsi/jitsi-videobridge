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

package org.jitsi

import com.typesafe.config.ConfigFactory
import io.kotlintest.Spec
import io.kotlintest.specs.ShouldSpec
import org.jitsi.config.ConfigurationServiceConfigSource
import org.jitsi.config.JitsiConfig
import org.jitsi.config.TypesafeConfigSource
import java.io.StringReader

/**
 * A helper class for testing configuration properties
 */
abstract class ConfigTest : ShouldSpec() {
    private val legacyService = TestReadOnlyConfigurationService()
    private val legacyConfig = ConfigurationServiceConfigSource("legacy", legacyService)

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        JitsiConfig.legacyConfig = legacyConfig
    }

    fun withLegacyConfig(props: String) {
        legacyService.props.load(StringReader(props))
    }

    fun withNewConfig(config: String) {
        JitsiConfig.newConfig = TypesafeConfigSource("new", ConfigFactory.parseString(config))
    }
}
