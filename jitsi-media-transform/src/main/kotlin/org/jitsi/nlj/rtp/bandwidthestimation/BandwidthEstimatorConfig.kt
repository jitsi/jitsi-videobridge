package org.jitsi.nlj.rtp.bandwidthestimation

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config

class BandwidthEstimatorConfig {
    companion object {
        val engine: BandwidthEstimatorEngine by config {
            "jmt.bwe.engine".from(JitsiConfig.newConfig)
                .convertFrom<String> { BandwidthEstimatorEngine.valueOf(it) }
        }
    }
}

enum class BandwidthEstimatorEngine {
    GoogleCc,
    GoogleCc2
}
