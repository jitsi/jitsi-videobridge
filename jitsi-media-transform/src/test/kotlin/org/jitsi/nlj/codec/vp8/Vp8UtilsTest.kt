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

package org.jitsi.nlj.codec.vp8

import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.data.forAll
import io.kotest.data.row

class Vp8UtilsTest : ShouldSpec() {

    init {
        context("getPicIdDelta") {
            should("work correctly") {
                forAll(
                    row(1, 10, -9),
                    row(10, 1, 9),
                    row(1, 32760, 9),
                    row(32760, 1, -9),
                    row(1234, 1234, 0)
                ) { a, b, expected ->
                    Vp8Utils.getExtendedPictureIdDelta(a, b) shouldBe expected
                }
            }
        }
        context("applyPicIdDelta") {
            should("work correctly") {
                forAll(
                    row(10, -9, 1),
                    row(1, 9, 10),
                    row(32760, 9, 1),
                    row(1, -9, 32760),
                    row(1234, 0, 1234)
                ) { start, delta, expected ->
                    Vp8Utils.applyExtendedPictureIdDelta(start, delta) shouldBe expected
                }
            }
        }
        context("getTl0PicIdxDelta") {
            should("work correctly") {
                forAll(
                    row(1, 10, -9),
                    row(10, 1, 9),
                    row(1, 250, 7),
                    row(250, 1, -7),
                    row(34, 34, 0)
                ) { a, b, expected ->
                    Vp8Utils.getTl0PicIdxDelta(a, b) shouldBe expected
                }
            }
        }
        context("applyTl0PicIdxDelta") {
            should("work correctly") {
                forAll(
                    row(10, -9, 1),
                    row(1, 9, 10),
                    row(250, 7, 1),
                    row(1, -7, 250),
                    row(34, 0, 34)
                ) { start, delta, expected ->
                    Vp8Utils.applyTl0PicIdxDelta(start, delta) shouldBe expected
                }
            }
        }
    }
}
