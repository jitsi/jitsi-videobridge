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
package org.jitsi.rtp2

import java.nio.ByteBuffer

fun parseId(buf: ByteBuffer): Int = 42
fun parseData(buf: ByteBuffer): ByteBuffer = buf

abstract class GenericField {
    abstract val id: Int
    abstract val data: ByteBuffer
}

open class OneByteFieldType(
    override val id: Int,
    override val data: ByteBuffer
) : GenericField() {

    constructor(buf: ByteBuffer) : this(parseId(buf), parseData(buf))
}

class SpecificOneByteFieldType(value: Byte) : OneByteFieldType(42, ByteBuffer.allocate(1)) {
    override val id: Int
        get() = super.id
}
