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
import org.jitsi.config.NewJitsiConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.config
import org.jitsi.xmpp.mucclient.MucClientConfiguration

class XmppClientConnectionConfig {
    val clientConfigs: List<MucClientConfiguration> by config {
        retrieve("Props from legacy config") {
            // TODO: This is tricky.  We no longer parse the old config via typesafe, so we can't
            // get a config object of the prefix and ConfigSource doesn't expose a
            // 'getPropertyNamesByPrefix' method, so for now we grab the ConfigurationService
            // instance from NewJitsiConfig directly.  It works, but wouldn't play nicely with
            // testing and is pretty ugly.
            MucClientConfiguration.loadFromConfigService(
                NewJitsiConfig.SipCommunicatorProps,
                "org.jitsi.videobridge.xmpp.user.",
                true
            ).toList().takeIf { it.isNotEmpty() } ?: throw ConfigException.UnableToRetrieve.NotFound("no configs found")
        }
        retrieve("videobridge.apis.xmpp-client.configs".from(NewJitsiConfig.newConfig)
            .asType<ConfigObject>()
            .andConvertBy { cfg ->
                cfg.entries.map { it.toMucClientConfiguration() }
            }
        )
    }
}

private fun MutableMap.MutableEntry<String, ConfigValue>.toMucClientConfiguration(): MucClientConfiguration {
    val config = MucClientConfiguration(this.key)
    (value as? ConfigObject)?.let {
        it.forEach { (propName, propValue) ->
            config.setProperty(propName, propValue.unwrapped().toString())
        }
    } ?: run { throw Exception("Invalid muc client configuration. " +
            "Expected type ConfigObject but got ${value.unwrapped()::class.java}") }
    return config
}
