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
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.optionalconfig
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class VideobridgeConfig {
    val shutdownSourcePattern: Pattern? by optionalconfig {
        "org.jitsi.videobridge.shutdown.ALLOWED_SOURCE_REGEXP"
            .from(JitsiConfig.legacyConfig)
            .convertFrom<String> {
                if (it.isNotBlank()) {
                    try {
                        Pattern.compile(it)
                    } catch (e: PatternSyntaxException) {
                        throw ConfigException.UnableToRetrieve.WrongType(
                            "shutdownSourceRegex expected valid regex pattern: $e")
                    }
                } else {
                    throw ConfigException.UnableToRetrieve.NotFound("not found")
                }
            }
        "videobridge.apis.xmpp-client.allow-shutdown-pattern".from(JitsiConfig.newConfig)
    }
}
