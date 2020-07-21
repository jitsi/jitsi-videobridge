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

import io.kotlintest.IsolationMode
import io.kotlintest.Spec
import io.kotlintest.specs.ShouldSpec
import org.jitsi.config.NewJitsiConfig
import org.jitsi.metaconfig.MapConfigSource
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.metaconfig.StdOutLogger

/**
 * A helper class for testing configuration properties
 */
abstract class ConfigTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    protected val legacyConfig = MapConfigSource("legacy")
    protected val newConfig = MapConfigSource("new")

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        NewJitsiConfig.legacyConfig = legacyConfig
        NewJitsiConfig.newConfig = newConfig
        MetaconfigSettings.logger = StdOutLogger
    }

    override fun afterSpec(spec: Spec) {
        super.afterSpec(spec)
        NewJitsiConfig.legacyConfig = NewJitsiConfig.SipCommunicatorPropsConfigSource
        NewJitsiConfig.newConfig = NewJitsiConfig.TypesafeConfig
    }
}
