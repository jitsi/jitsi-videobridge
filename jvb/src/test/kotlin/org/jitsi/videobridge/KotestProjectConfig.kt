/*
 * Copyright @ 2022 - present 8x8, Inc.
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

import io.kotest.core.config.AbstractProjectConfig
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer

class KotestProjectConfig : AbstractProjectConfig() {
    override suspend fun beforeProject() = super.beforeProject().also {
        // The only purpose of config caching is performance. We always want caching disabled in tests (so we can
        // freely modify the config without affecting other tests executing afterwards).
        MetaconfigSettings.cacheEnabled = false

        // By default, MetricsContainer throws an exception when attempting to register a metric with the same name
        // as an existing metric. MetricsContainer is a singleton with a static instance and is preserved across tests.
        // We want this flag disabled so that instancing classes that register metrics (such as Videobridge) does not
        // result in runtime exceptions. Note that metrics with matching name and type are returned this way.
        // As such, tests that rely on metric values changing may require additional steps.
        VideobridgeMetricsContainer.instance.checkForNameConflicts = false
    }
}
