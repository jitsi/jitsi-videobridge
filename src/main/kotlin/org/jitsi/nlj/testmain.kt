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
package org.jitsi.nlj

import java.nio.ByteBuffer

fun main(args: Array<String>) {
    val b = ByteBuffer.allocate(4)
    b.put(0x1)
    b.put(0x2)
    b.put(0x3)
    b.put(0x4)
    b.flip()

    val s = b.short

    val secondHalf = b.slice()
    println(secondHalf.array()[0])
    println(secondHalf.array()[1])
}
