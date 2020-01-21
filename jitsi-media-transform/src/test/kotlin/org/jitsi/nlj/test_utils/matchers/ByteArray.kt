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

package org.jitsi.nlj.test_utils.matchers

import io.kotlintest.Matcher
import io.kotlintest.MatcherResult
import org.jitsi.rtp.extensions.bytearray.toHex

fun haveSameContentAs(expected: ByteArray) = object : Matcher<ByteArray> {
    override fun test(value: ByteArray): MatcherResult {
        var matches = true
        if (expected.size != value.size) {
            matches = false
        } else {
            for (i in (0 until expected.size)) {
                if (value.get(i) != expected.get(i)) {
                    matches = false
                    break
                }
            }
        }
        return MatcherResult(matches,
            "\n${value.toHex()}\nwas supposed to be:\n${expected.toHex()}",
            "\n${value.toHex()}\nshould not have equaled \n${expected.toHex()}"
        )
    }
}
