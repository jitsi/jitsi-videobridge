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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import java.time.Duration

class VideobridgeExpireThreadConfig {
    val inactivityTimeout: Duration by config("videobridge.entity-expiration.timeout".from(JitsiConfig.newConfig))

    val interval: Duration by config {
        "org.jitsi.videobridge.EXPIRE_CHECK_SLEEP_SEC".from(JitsiConfig.legacyConfig)
            .convertFrom<Long>(Duration::ofSeconds)
        "videobridge.entity-expiration.check-interval".from(JitsiConfig.newConfig)
    }
}
