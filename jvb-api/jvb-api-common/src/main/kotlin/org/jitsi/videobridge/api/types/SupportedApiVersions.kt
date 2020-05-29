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

package org.jitsi.videobridge.api.types

/**
 * An enum representing an API versions.  The value is a ordinal value to
 * denote the version in a monotonically-increasing order, such that versions
 * can be compared.
 */
enum class ApiVersion(val value: Int) {
    V1(1);

    override fun toString(): String = "v$value"

    companion object {
        private val valueMap = values().associateBy { "v${it.value}" }
        /**
         * Get an [ApiVersion] instance from a [String] formatted as:
         * v<API VERSION NUMBER>
         */
        fun fromString(versionStr: String): ApiVersion? =
            valueMap.getOrDefault(versionStr, null)
    }
}

@Suppress("unused", "MemberVisibilityCanBePrivate")
data class SupportedApiVersions(val supportedVersions: List<ApiVersion>) {
    constructor(vararg versions: ApiVersion) : this(versions.toList())

    fun supports(apiVersion: ApiVersion): Boolean =
        supportedVersions.contains(apiVersion)

    /**
     * Return the maximum supported [ApiVersion] supported by both this
     * [SupportedApiVersions] instance and [other], or null if there is
     * no intersection.
     */
    fun maxSupported(other: SupportedApiVersions): ApiVersion? =
        supportedVersions.sorted().find(other::supports)

    // So we can add extensions to it
    companion object
}

/**
 * Serialize to a [String] which can be placed in the JVB's presence
 */
fun SupportedApiVersions.toPresenceString(): String =
    supportedVersions.joinToString(separator = ",")

/**
 * Deserialize from a presence [String] into a [SupportedApiVersions] instance.
 */
fun SupportedApiVersions.Companion.fromPresenceString(str: String): SupportedApiVersions =
    SupportedApiVersions(str.split(",").mapNotNull(ApiVersion.Companion::fromString))
