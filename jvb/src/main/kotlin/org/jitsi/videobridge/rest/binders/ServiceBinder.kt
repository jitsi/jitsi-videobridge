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
import org.jitsi.health.HealthCheckServiceSupplier
import org.jitsi.version.VersionServiceSupplier
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.health.jvbHealthCheckServiceSupplier
import org.jitsi.videobridge.stats.StatsManager
import org.jitsi.videobridge.version.jvbVersionServiceSupplier
import org.jitsi.videobridge.xmpp.XmppConnection

class ServiceBinder(
    val videobridge: Videobridge,
    val xmppConnection: XmppConnection,
    val statsManager: StatsManager
) : AbstractBinder() {
    override fun configure() {
        bind(videobridge).to(Videobridge::class.java)
        bind(statsManager).to(StatsManager::class.java)
        bind(xmppConnection).to(XmppConnection::class.java)
        bind(jvbHealthCheckServiceSupplier).to(HealthCheckServiceSupplier::class.java)
        bind(jvbVersionServiceSupplier).to(VersionServiceSupplier::class.java)
    }
}
