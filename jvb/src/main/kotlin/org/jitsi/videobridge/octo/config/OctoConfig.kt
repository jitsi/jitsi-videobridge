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

import com.typesafe.config.ConfigObject
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import org.jitsi.videobridge.config.NewJitsiConfig
import org.jitsi.metaconfig.optionalconfig

class OctoConfig {
    val recvQueueSize: Int by config("videobridge.octo.recv-queue-size".from(NewJitsiConfig.newConfig))

    val sendQueueSize: Int by config("videobridge.octo.send-queue-size".from(NewJitsiConfig.newConfig))

    val enabled: Boolean by config {
        // The legacy config file doesn't have an 'enabled' property,
        // instead it was based on the values of the parameters.  Here,
        // we simulate a legacy 'enabled' value based on the results
        // of validating the other properties in the legacy config
        // file.
        retrieve("org.jitsi.videobridge.octo".from(NewJitsiConfig.legacyConfig)
                .asType<ConfigObject>()
                .andConvertBy {
                    val cfg = it.toConfig()
                    if (cfg.hasPath("BIND_ADDRESS") && cfg.hasPath("BIND_PORT")) {
                        val bindAddress = cfg.getString("BIND_ADDRESS")
                        val bindPort = cfg.getInt("BIND_PORT")
                        bindAddress != null && (bindPort.isUnprivilegedPort())
                    } else {
                        false
                    }
                }
        )
        retrieve("videobridge.octo.enabled".from(NewJitsiConfig.newConfig))
    }

    val region: String? by optionalconfig {
        retrieve("org.jitsi.videobridge.REGION".from(NewJitsiConfig.legacyConfig))
        retrieve("videobridge.octo.region".from(NewJitsiConfig.newConfig))
    }

    val bindAddress: String by config {
        retrieve("org.jitsi.videobridge.octo.BIND_ADDRESS".from(NewJitsiConfig.legacyConfig))
        retrieve("videobridge.octo.bind-address".from(NewJitsiConfig.newConfig))
    }

    val bindPort: Int by config {
        retrieve("org.jitsi.videobridge.octo.BIND_PORT".from(NewJitsiConfig.legacyConfig))
        retrieve("videobridge.octo.bind-port".from(NewJitsiConfig.newConfig))
    }

    /**
     * If publicAddress doesn't have a value, default to the value of bindAddress.
     * Note: we can't use a substitution in reference.conf because that won't take into account
     * reading a value from the legacy config file
     */
    val publicAddress: String by config {
        retrieve("org.jitsi.videobridge.octo.PUBLIC_ADDRESS".from(NewJitsiConfig.legacyConfig))
        retrieve("videobridge.octo.public-address".from(NewJitsiConfig.newConfig))
        retrieve("bindAddress") { bindAddress }
    }

    companion object {
        /**
         * NOTE(brian): Define this here because many classes want to access it from a static context, but
         * I think we could tweak that if we wanted.
         */
        @JvmField
        val config = OctoConfig()
    }
}

private fun Int.isUnprivilegedPort(): Boolean = this in 1024..65535
