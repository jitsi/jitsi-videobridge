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

package org.jitsi.rtp.util

/**
 * All [ByteArray]s that the library needs for packets will be acquired via [getArray].
 *
 * When we're done with a buffer, we'll pass it to [returnArray].  These methods
 * can be overridden so that a user of this library can use a pool for the buffers.
 *
 * @author Brian Baldino
 */
class BufferPool {
    companion object {
        var getArray: (Int) -> ByteArray = { size -> ByteArray(size) }
        var returnArray: (ByteArray) -> Unit = { }
    }
}
