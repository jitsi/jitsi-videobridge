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
import org.jitsi.health.HealthCheckService
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.stats.StatsCollector
import org.jitsi.videobridge.xmpp.XmppConnection

class ServiceBinder(
    private val videobridge: Videobridge,
    private val xmppConnection: XmppConnection,
    private val statsCollector: StatsCollector?,
    private val healthChecker: HealthCheckService
) : AbstractBinder() {
    override fun configure() {
        bind(videobridge).to(Videobridge::class.java)
        // We have to test this, because the nullablle 'StatsCollector?' type doesn't play
        // nicely in hk2 since we're binding to 'StatsCollector'
        if (statsCollector != null) {
            bind(statsCollector).to(StatsCollector::class.java)
        }
        bind(xmppConnection).to(XmppConnection::class.java)
        bind(healthChecker).to(HealthCheckService::class.java)
    }
}
