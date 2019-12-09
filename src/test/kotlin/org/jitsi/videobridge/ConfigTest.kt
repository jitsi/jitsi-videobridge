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

import io.kotlintest.IsolationMode
import io.kotlintest.specs.ShouldSpec
import org.jitsi.config.JitsiConfigFactory
import org.jitsi.utils.config.ConfigSource
import org.jitsi.videobridge.testutils.ConfigSourceWrapper

abstract class ConfigTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    protected fun withLegacyConfig(configSource: ConfigSource) {
        legacyConfigWrapper.innerConfig = configSource
    }

    protected fun withNewConfig(configSource: ConfigSource) {
        println("setting inner config on new config instance ${newConfigWrapper.hashCode()}")
        newConfigWrapper.innerConfig = configSource
    }

    protected companion object {
        private val legacyConfigWrapper = ConfigSourceWrapper().also {
            JitsiConfigFactory.legacyConfigSupplier = {
                println("in the test legacy config supplier!")
                it
            }
        }
        private val newConfigWrapper = ConfigSourceWrapper().also {
            JitsiConfigFactory.newConfigSupplier = {
                println("in the test new config supplier! returning instance ${it.hashCode()}")
                it
            }
        }
    }
}