/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.test_helpers.matchers

import io.kotlintest.Matcher
import io.kotlintest.Result
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.ByteArrayBuffer

fun ByteArrayBuffer.hasSameContentAs(other: ByteArrayBuffer): Boolean {
    if (other.length != length) {
        return false
    } else {
        for (i in (0 until length)) {
            if (buffer[offset + i] != other.buffer[other.offset + i]) {
                return false
            }
        }
    }

    return true
}

fun haveSameContentAs(expected: ByteArrayBuffer) = object : Matcher<ByteArrayBuffer> {
    override fun test(value: ByteArrayBuffer): Result {
        return Result(value.hasSameContentAs(expected),
            "\n${value.toHex()}\nwas supposed to be:\n${expected.toHex()}",
            "\n${value.toHex()}\nshould not have equaled \n${expected.toHex()}"
        )
    }
}
