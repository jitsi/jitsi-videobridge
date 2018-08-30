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

import org.jitsi.rtp.extensions.rewindOneByte
import java.nio.ByteBuffer

abstract class RtpHeaderExtension {
    /**
     * This extension's ID
     */
    abstract val id: Int
    /**
     * The length of the data chunk of this extension
     */
    abstract val lengthBytes: Int
    /**
     * The data for this extension
     */
    abstract val data: ByteBuffer

    /**
     * The size of the extension, in bytes.  This includes the ID, length
     * and data fields
     */
    abstract val size: Int

    private val Byte.isPadding: Boolean
        get() = this == 0.toByte()

    //TODO: do we need to put a size limit on this, in case the first byte
    // of whatever the next field is (the payload) is 0?
    // Returns the amount of padding consumed (in bytes)
    protected fun consumePadding(buf: ByteBuffer) {
        // At this point the buffer is at the end of the data.  Now we need
        // to (maybe) advance it further past any padding bytes.  Padding
        // bytes will always be 0
        var currByte: Byte = 0
        while (buf.hasRemaining() && currByte.isPadding) {
            currByte = buf.get()
        }
        if (currByte != 0.toByte()) {
            // We might have stopped because we reached the end of the buffer
            // or because we hit the payload after padding.  If we hit
            // the payload, the rewind the buffer by one so the next time
            // we read we get this next non-padding byte)
            // Now we've hit the ID of the next extension, so we need to rewind the buffer one
            // byte
            buf.rewindOneByte()
        }
    }

    abstract fun serializeToBuffer(buf: ByteBuffer)
}
