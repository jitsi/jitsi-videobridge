/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.rtp

import java.nio.ByteBuffer

fun parseId(buf: ByteBuffer): Int = 42
fun parseData(buf: ByteBuffer): ByteBuffer = buf

abstract class GenericField {
    abstract val id: Int
    abstract val data: ByteBuffer
}

open class OneByteFieldType : GenericField {
    final override val id: Int
    final override val data: ByteBuffer

    constructor(buf: ByteBuffer) {
        this.id = parseId(buf)
        this.data = parseData(buf)
    }

    constructor(id: Int, data: ByteBuffer) {
        this.id = id
        this.data = data
    }
}
