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

package org.jitsi.videobridge.load_management

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config

class PacketRateMeasurement(private val packetRate: Long) : JvbLoadMeasurement {
    override fun getLoad(): Double = packetRate.toDouble()

    override fun toString(): String = "RTP packet rate (up + down) of $packetRate pps"

    companion object {
        private val config = PacketRateMeasurementConfig()

        @JvmStatic
        val loadedThreshold = config.loadThreshold
        @JvmStatic
        val recoveryThreshold = config.recoverThreshold
    }
}

private class PacketRateMeasurementConfig {
    val loadThreshold: PacketRateMeasurement by config {
        "${JvbLoadMeasurement.CONFIG_BASE}.packet-rate.load-threshold"
            .from(JitsiConfig.newConfig)
            .convertFrom<Long>(::PacketRateMeasurement)
    }
    val recoverThreshold: PacketRateMeasurement by config {
        "${JvbLoadMeasurement.CONFIG_BASE}.packet-rate.recover-threshold"
            .from(JitsiConfig.newConfig)
            .convertFrom<Long>(::PacketRateMeasurement)
    }
}
