package org.jitsi.nlj.rtp.bandwidthestimation

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.util.Bandwidth

class BandwidthEstimatorConfig {
    companion object {
        val engine: BandwidthEstimatorEngine by config {
            "jmt.bwe.estimator.engine".from(JitsiConfig.newConfig)
                .convertFrom<String> { BandwidthEstimatorEngine.valueOf(it) }
        }

        val initBw: Bandwidth by config {
            "jmt.bwe.estimator.initial-bw".from(JitsiConfig.newConfig)
                .convertFrom<String> { Bandwidth.fromString(it) }
        }

        val minBw: Bandwidth by config {
            "jmt.bwe.google-cc.min-bw".from(JitsiConfig.newConfig)
                .convertFrom<String> { Bandwidth.fromString(it) }.softDeprecated("use jmt.bwe.estimator.min-bw")
            "jmt.bwe.estimator.min-bw".from(JitsiConfig.newConfig)
                .convertFrom<String> { Bandwidth.fromString(it) }
        }

        val maxBw: Bandwidth by config {
            "jmt.bwe.google-cc.max-bw".from(JitsiConfig.newConfig)
                .convertFrom<String> { Bandwidth.fromString(it) }.softDeprecated("use jmt.bwe.estimator.max-bw")
            "jmt.bwe.estimator.max-bw".from(JitsiConfig.newConfig)
                .convertFrom<String> { Bandwidth.fromString(it) }
        }
    }
}

enum class BandwidthEstimatorEngine {
    GoogleCc,
    GoogleCc2
}
