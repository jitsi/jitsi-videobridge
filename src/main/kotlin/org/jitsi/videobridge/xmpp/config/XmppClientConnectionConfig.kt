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

package org.jitsi.videobridge.xmpp.config

import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import org.jitsi.utils.config.dsl.multiProperty
import org.jitsi.xmpp.mucclient.MucClientConfiguration

class XmppClientConnectionConfig {
    companion object {
        private val clientConnectionConfigs = multiProperty<List<MucClientConfiguration>> {
            property {
                name("org.jitsi.videobridge.xmpp.user")
                readOnce()
                retrievedAs<ConfigObject>() convertedBy { cfg ->
                    cfg.entries.map { it.toMucClientConfiguration() }
                }
            }
            property {
                name("videobridge.apis.xmpp-client.configs")
                readOnce()
                retrievedAs<ConfigObject>() convertedBy { cfg ->
                    cfg.entries.map { it.toMucClientConfiguration() }
                }
            }
        }

        private fun MutableMap.MutableEntry<String, ConfigValue>.toMucClientConfiguration(): MucClientConfiguration {
            val config = MucClientConfiguration(this.key)
            (value as? ConfigObject)?.let {
                it.forEach { (propName, propValue) ->
                    config.setProperty(propName, propValue.unwrapped().toString())
                }
            }
            return config
        }

        @JvmStatic
        fun getClientConfigs(): List<MucClientConfiguration> =
            clientConnectionConfigs.value
    }
}