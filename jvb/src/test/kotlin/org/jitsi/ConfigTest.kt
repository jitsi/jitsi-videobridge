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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.config.useLegacyConfig
import org.jitsi.config.useNewConfig
import org.jitsi.metaconfig.MetaconfigSettings

/**
 * A helper class for testing configuration properties
 */
abstract class ConfigTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        MetaconfigSettings.cacheEnabled = false
    }

    override fun afterSpec(spec: Spec) {
        super.afterSpec(spec)
        MetaconfigSettings.cacheEnabled = true
    }

    inline fun withLegacyConfig(props: String, block: () -> Unit) {
        useLegacyConfig(name = "legacy-${this::class.simpleName}", props = props, block = block)
    }

    inline fun withNewConfig(config: String, block: () -> Unit) {
        useNewConfig("new-${this::class.simpleName}", config, false, block)
    }

    inline fun withNewConfig(config: String, loadDefaults: Boolean, block: () -> Unit) {
        useNewConfig("new-${this::class.simpleName}", config, loadDefaults, block)
    }
}
