/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.rtp.util

import java.nio.ByteBuffer

// TODO: i think these should only be used for tests.  Move them?
fun byteBufferOf(vararg elements: Byte): ByteBuffer = ByteBuffer.wrap(byteArrayOf(*elements))

fun byteBufferOf(vararg elements: Number): ByteBuffer {
    val bytes = elements.map { it.toByte() }.toByteArray()
    return ByteBuffer.wrap(bytes)
}
