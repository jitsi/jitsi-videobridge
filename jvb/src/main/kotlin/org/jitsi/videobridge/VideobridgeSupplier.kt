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

import org.jitsi.videobridge.service.ServiceSupplier

/**
 * Rather than creating [Videobridge] as part of an OSGi service,
 * centralize its creation here.  This is a temporary solution until
 * all other services are moved off of OSGi; once that is done then
 * [Videobridge] can be created normally (in main, most likely) and
 * passed to the other entities when they are created.
 *
 * NOTE: This is intentionally implemented as a non-static instance
 * for testability.  Classes should take in an instance of [VideobridgeSupplier]
 * which can be swapped for testing.  Production code should use
 * [singleton] as the instance it passes everywhere.
 *
 * Note: this class needs to be open for testing (java/mockito can't mock
 * a final class).  If we move tests to kotlin/mockk, we can remove it.
 */
open class VideobridgeSupplier : ServiceSupplier<Videobridge> {
    private val videobridge: Videobridge by lazy {
        Videobridge()
    }

    override fun get(): Videobridge = videobridge
}

@JvmField
val singleton = VideobridgeSupplier()
