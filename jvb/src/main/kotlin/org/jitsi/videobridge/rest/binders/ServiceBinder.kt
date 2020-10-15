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
import org.jitsi.videobridge.health.JvbHealthCheckServiceSupplier
import org.jitsi.videobridge.stats.StatsCollector
import org.jitsi.videobridge.version.JvbVersionServiceSupplier
import org.jitsi.videobridge.xmpp.XmppConnection

class ServiceBinder(
    private val videobridge: Videobridge,
    private val xmppConnection: XmppConnection,
    private val statsCollector: StatsCollector
) : AbstractBinder() {
    override fun configure() {
        bind(videobridge).to(Videobridge::class.java)
        bind(statsCollector).to(StatsCollector::class.java)
        bind(xmppConnection).to(XmppConnection::class.java)
        // These are still suppliers rather than direct instances because that's what
        // Jicoco requires
        bind(JvbHealthCheckServiceSupplier(videobridge.healthChecker)).to(HealthCheckServiceSupplier::class.java)
        bind(JvbVersionServiceSupplier(videobridge.versionService)).to(VersionServiceSupplier::class.java)
    }
}
