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

package org.jitsi.videobridge.api.types.v1

import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.health.HealthCheckIQ
import org.jivesoftware.smack.packet.IQ

/**
 * The [ConferenceManager] is an interface that correlates to the methods
 * currently in the 'Videobridge' class in JVB that the API will need to
 * interact with when handling incoming messages.  This interface exists
 * to allow a decoupling between the API and the JVB so that the API can
 * live as a separate artifact and avoid a circular dependency (since the
 * JVB will rely on this artifact).
 */
interface ConferenceManager {
    fun handleColibriConferenceIQ(conferenceIQ: ColibriConferenceIQ): IQ
    fun handleHealthIq(healthCheckIQ: HealthCheckIQ): IQ
}
