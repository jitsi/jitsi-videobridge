package org.jitsi.videobridge

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from

class SourceNameSignalingConfig {
    val enabled: Boolean by config("videobridge.source-name-signaling.enabled".from(JitsiConfig.newConfig))
    fun isEnabled() = enabled

    companion object {
        @JvmField
        val config = SourceNameSignalingConfig()
    }
}
