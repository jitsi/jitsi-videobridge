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

package org.jitsi.videobridge

import io.kotlintest.Spec
import org.jitsi.config.ConfigSourceWrapper
import org.jitsi.config.ConfigTest
import org.jitsi.config.JitsiConfigFactory
import org.jitsi.utils.config.ConfigSource

/**
 * This class is/should be a duplicate of the one in jicoco-kotlin.  We can't
 * move it to jicoco-test-kotlin because it would cause a circular maven
 * dependency
 */
abstract class JitsiConfigTest : ConfigTest() {

    override fun withLegacyConfig(configSource: ConfigSource) {
        legacyConfigWrapper.innerConfig = configSource
    }

    override fun withNewConfig(configSource: ConfigSource) {
        newConfigWrapper.innerConfig = configSource
    }

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        JitsiConfigFactory.legacyConfigSupplier = { legacyConfigWrapper }
        JitsiConfigFactory.newConfigSupplier = { newConfigWrapper }
    }

    companion object {
        private val legacyConfigWrapper = ConfigSourceWrapper()
        private val newConfigWrapper = ConfigSourceWrapper()
    }
}
