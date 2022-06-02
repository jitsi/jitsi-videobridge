/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.nlj

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RtpLayerDescTest : FunSpec({

    test("VP8 layer ids") {
        // mostly for documenting the encoding -> index mapping.
        val vp8Layers = arrayOf(
            RtpLayerDesc(0, 0, 0, 180, 7.5),
            RtpLayerDesc(0, 1, 0, 180, 15.0),
            RtpLayerDesc(0, 2, 0, 180, 30.0),
            RtpLayerDesc(1, 0, 0, 360, 7.5),
            RtpLayerDesc(1, 1, 0, 360, 15.0),
            RtpLayerDesc(1, 2, 0, 360, 30.0),
            RtpLayerDesc(2, 0, 0, 720, 7.5),
            RtpLayerDesc(2, 1, 0, 720, 15.0),
            RtpLayerDesc(2, 2, 0, 720, 30.0)
        )

        vp8Layers[0].index shouldBe 0
        vp8Layers[1].index shouldBe 1
        vp8Layers[2].index shouldBe 2
        vp8Layers[3].index shouldBe 64
        vp8Layers[4].index shouldBe 65
        vp8Layers[5].index shouldBe 66
        vp8Layers[6].index shouldBe 128
        vp8Layers[7].index shouldBe 129
        vp8Layers[8].index shouldBe 130

        vp8Layers[0].layerId shouldBe 0
        vp8Layers[1].layerId shouldBe 1
        vp8Layers[2].layerId shouldBe 2
        vp8Layers[3].layerId shouldBe 0
        vp8Layers[4].layerId shouldBe 1
        vp8Layers[5].layerId shouldBe 2
        vp8Layers[6].layerId shouldBe 0
        vp8Layers[7].layerId shouldBe 1
        vp8Layers[8].layerId shouldBe 2
    }

    test("VP9 layer ids") {
        // same here, mostly for documenting the encoding -> index mapping.
        val vp9Layers = arrayOf(
            RtpLayerDesc(0, 0, 0, 180, 7.5),
            RtpLayerDesc(0, 1, 0, 180, 15.0),
            RtpLayerDesc(0, 2, 0, 180, 30.0),
            RtpLayerDesc(0, 0, 1, 360, 7.5),
            RtpLayerDesc(0, 1, 1, 360, 15.0),
            RtpLayerDesc(0, 2, 1, 360, 30.0),
            RtpLayerDesc(0, 0, 2, 720, 7.5),
            RtpLayerDesc(0, 1, 2, 720, 15.0),
            RtpLayerDesc(0, 2, 2, 720, 30.0)
        )

        vp9Layers[0].index shouldBe 0
        vp9Layers[1].index shouldBe 1
        vp9Layers[2].index shouldBe 2
        vp9Layers[3].index shouldBe 8
        vp9Layers[4].index shouldBe 9
        vp9Layers[5].index shouldBe 10
        vp9Layers[6].index shouldBe 16
        vp9Layers[7].index shouldBe 17
        vp9Layers[8].index shouldBe 18

        vp9Layers[0].layerId shouldBe 0
        vp9Layers[1].layerId shouldBe 1
        vp9Layers[2].layerId shouldBe 2
        vp9Layers[3].layerId shouldBe 8
        vp9Layers[4].layerId shouldBe 9
        vp9Layers[5].layerId shouldBe 10
        vp9Layers[6].layerId shouldBe 16
        vp9Layers[7].layerId shouldBe 17
        vp9Layers[8].layerId shouldBe 18
    }
})
