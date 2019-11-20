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

package org.jitsi.videobridge.config

import org.jitsi.utils.config.ConfigSource
import org.jitsi.utils.logging2.LoggerImpl

class JvbConfig {
    companion object {
        private val logger = LoggerImpl(JvbConfig::class.java.name)
        lateinit var legacyConfig: ConfigSource
        lateinit var newConfig: ConfigSource
        // Must be assigned manually by whatever has access to the command-line args
        lateinit var commandLineArgs: ConfigSource

        init {
            loadConfig()
        }

        private fun loadConfig() {
            legacyConfig = LegacyConfig()
            newConfig = NewConfig()
        }

        fun reloadConfig() {
            logger.info("Reloading config")
            //TODO: where to call ConfigFactory.invalidateCaches?
            loadConfig()
        }
    }
}