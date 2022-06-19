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

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import org.jitsi.rtp.extensions.compareToFromBeginning
import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer

fun haveSameContentAs(expected: ByteBuffer) = object : Matcher<ByteBuffer> {
    override fun test(value: ByteBuffer): MatcherResult {
        return MatcherResult(
            value.compareToFromBeginning(expected) == 0,
            { "Buffer\n${value.toHex()}\nwas supposed to be:\n${expected.toHex()}" },
            { "Buffer\n${value.toHex()}\nshould not have equaled buffer\n${expected.toHex()}" }
        )
    }
}
