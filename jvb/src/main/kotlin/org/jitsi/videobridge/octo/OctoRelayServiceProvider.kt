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

package org.jitsi.videobridge.octo

import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.octo.config.OctoConfig

class OctoRelayServiceProvider {
    private val octoRelayService: OctoRelayService? by lazy {
        if (OctoConfig.config.enabled) {
            try {
                OctoRelayService()
            } catch (t: Throwable) {
                logger.error("Error initializing OctoRelayService", t)
                null
            }
        } else {
            logger.info("Octo disabled in configuration.")
            null
        }
    }

    fun get(): OctoRelayService? = octoRelayService

    companion object {
        val logger = createLogger()
    }
}

private val supplierSingleton: OctoRelayServiceProvider = OctoRelayServiceProvider()

fun singleton(): OctoRelayServiceProvider = supplierSingleton
