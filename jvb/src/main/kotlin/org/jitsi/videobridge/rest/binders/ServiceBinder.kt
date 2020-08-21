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

package org.jitsi.videobridge.rest.binders

import org.glassfish.hk2.utilities.binding.AbstractBinder
import org.jitsi.videobridge.VideobridgeSupplier
import org.jitsi.videobridge.health.HealthCheckServiceSupplier
import org.jitsi.videobridge.health.singleton as healthCheckServiceSupplierSingleton
import org.jitsi.videobridge.stats.StatsManagerSupplier
import org.jitsi.videobridge.version.VersionServiceSupplier
import org.jitsi.videobridge.version.singleton as versionServiceSupplierSingleton
import org.jitsi.videobridge.stats.singleton as statsManagerSupplierSingleton
import org.jitsi.videobridge.singleton as videobridgeSupplierSingleton

class ServiceBinder : AbstractBinder() {
    override fun configure() {
        bind(videobridgeSupplierSingleton).to(VideobridgeSupplier::class.java)
        bind(statsManagerSupplierSingleton).to(StatsManagerSupplier::class.java)
        bind(versionServiceSupplierSingleton).to(VersionServiceSupplier::class.java)
        bind(healthCheckServiceSupplierSingleton).to(HealthCheckServiceSupplier::class.java)
    }
}
