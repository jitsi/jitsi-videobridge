package org.jitsi.videobridge

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

object GlobalOTel {
    val sdk: OpenTelemetry by lazy {
        AutoConfiguredOpenTelemetrySdk.builder().build().openTelemetrySdk
    }
}
