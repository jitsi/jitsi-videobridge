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

package org.jitsi.videobridge.version

import org.jitsi.utils.version.Version
import org.jitsi.utils.version.VersionImpl
import org.jitsi.utils.version.VersionService
import java.util.regex.Matcher
import java.util.regex.Pattern

class JvbVersionService : VersionService {
    private val versionString: String? by lazy {
        JvbVersionService::class.java.`package`.implementationVersion
    }

    private val version: Version by lazy {
        parseVersionString(versionString)
    }

    override fun getCurrentVersion(): Version = version

    override fun parseVersionString(version: String?): Version {
        val matcher = Pattern.compile("(\\d*)\\.(\\d*)-(.*)").matcher(version ?: "").apply { find() }
        val majorVersion = matcher.groupOrNull(1)?.toInt() ?: defaultMajorVersion
        val minorVersion = matcher.groupOrNull(2)?.toInt() ?: defaultMinorVersion
        val buildId = matcher.groupOrNull(3) ?: defaultBuildId

        return VersionImpl(
            "JVB",
            majorVersion,
            minorVersion,
            buildId
        )
    }

    companion object {
        private const val defaultMajorVersion = 2
        private const val defaultMinorVersion = 1
        private val defaultBuildId: String? = null
    }
}

private fun Matcher.groupOrNull(groupId: Int): String? {
    return try {
        group(groupId)
    } catch (t: Throwable) {
        null
    }
}
