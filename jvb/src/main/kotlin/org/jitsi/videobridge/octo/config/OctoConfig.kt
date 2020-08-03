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

package org.jitsi.videobridge.octo.config

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import org.jitsi.metaconfig.optionalconfig

class OctoConfig {
    val recvQueueSize: Int by config("videobridge.octo.recv-queue-size".from(JitsiConfig.newConfig))

    val sendQueueSize: Int by config("videobridge.octo.send-queue-size".from(JitsiConfig.newConfig))

    // We grab these two properties from the legacy config separately here
    // because we use them to infer a legacy value of 'enabled' (which was
    // based on the presence of these properties) and as potential values
    // in each of the individual bindAddress and bindPort properties.
    private val legacyBindAddress: String? by optionalconfig(
        "org.jitsi.videobridge.octo.BIND_ADDRESS".from(JitsiConfig.legacyConfig))
    private val legacyBindPort: Int? by optionalconfig(
        "org.jitsi.videobridge.octo.BIND_PORT".from(JitsiConfig.legacyConfig))

    val enabled: Boolean by config {
        // The legacy config file doesn't have an 'enabled' property,
        // instead it was based on the values of the parameters.  Here,
        // we simulate a legacy 'enabled' value based on the results
        // of validating the other properties in the legacy config
        // file.  If neither property is present, we consider the field
        // "not found" in the legacy config.
        "Legacy Octo relay enabled" {
            if (legacyBindAddress == null && legacyBindPort == null) {
                throw ConfigException.UnableToRetrieve.NotFound("not found")
            }
            legacyBindAddress != null && legacyBindPort?.isUnprivilegedPort() == true
        }
        "videobridge.octo.enabled".from(JitsiConfig.newConfig)
    }

    val region: String? by optionalconfig {
        "org.jitsi.videobridge.REGION".from(JitsiConfig.legacyConfig)
        "videobridge.octo.region".from(JitsiConfig.newConfig)
    }

    val bindAddress: String by config {
        "bind address from legacy config" { legacyBindAddress!! }
        "videobridge.octo.bind-address".from(JitsiConfig.newConfig)
    }

    val bindPort: Int by config {
        "bind port from legacy config" { legacyBindPort!! }
        "videobridge.octo.bind-port".from(JitsiConfig.newConfig)
    }

    /**
     * If publicAddress doesn't have a value, default to the value of bindAddress.
     * Note: we can't use a substitution in reference.conf because that won't take into account
     * reading a value from the legacy config file
     */
    val publicAddress: String by config {
        "org.jitsi.videobridge.octo.PUBLIC_ADDRESS".from(JitsiConfig.legacyConfig)
        "videobridge.octo.public-address".from(JitsiConfig.newConfig)
        "bindAddress" { bindAddress }
    }

    companion object {
        @JvmField
        val config = OctoConfig()
    }
}

private fun Int.isUnprivilegedPort(): Boolean = this in 1024..65535
