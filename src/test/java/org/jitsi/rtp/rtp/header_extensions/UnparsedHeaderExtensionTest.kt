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

package org.jitsi.rtp.rtp.header_extensions

import io.kotlintest.should
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import org.jitsi.rtp.extensions.plus
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

open class ExtensionInfo(
    val type: HeaderExtensionType,
    val id: Int,
    data: ByteBuffer,
    /**
     * We take in what the expected one-byte buffer would look like so that it
     * can be used for validation.  the two-byte version is derived from
     * the data (I didn't want to rely on the actual code for this, as that's
     * part of what we're testing).
     */
    expectedOneByteBuffer: ByteBuffer
) {
    private val _data = data
    val data: ByteBuffer get() = _data.duplicate()

    private val _expectedBuffer = expectedOneByteBuffer
    val expectedOneByteBuffer: ByteBuffer get() = _expectedBuffer.duplicate()

    private val _expectedTwoByteBuffer: ByteBuffer =
        byteBufferOf(id.toByte(), data.limit().toByte()) + this.data
    val expectedTwoByteBuffer: ByteBuffer get() = _expectedTwoByteBuffer.duplicate()

}
class OneByteExtensionInfo(
    id: Int,
    data: ByteBuffer,
    expectedBuffer: ByteBuffer
) : ExtensionInfo(HeaderExtensionType.ONE_BYTE_HEADER_EXT, id, data, expectedBuffer)

class TwoByteExtensionInfo(
    id: Int,
    data: ByteBuffer,
    expectedBuffer: ByteBuffer
) : ExtensionInfo(HeaderExtensionType.TWO_BYTE_HEADER_EXT, id, data, expectedBuffer)

fun idLengthByte(id: Int, length: Int): Byte {
    return ((id shl 4) or length).toByte()
}

class UnparsedHeaderExtensionTest : ShouldSpec() {
    companion object {

        val oneByteHeaderExtension = OneByteExtensionInfo(
            1,
            byteBufferOf(0x42, 0x42),
            byteBufferOf(idLengthByte(1, 1), 0x42, 0x42)
        )

        val twoByteHeaderExtensionThatCouldBeOneByte = TwoByteExtensionInfo(
            oneByteHeaderExtension.id,
            oneByteHeaderExtension.data,
            oneByteHeaderExtension.expectedOneByteBuffer
        )

        val twoByteHeaderExtension = TwoByteExtensionInfo(
            1,
            byteBufferOf(
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42
            ),
            byteBufferOf(
                0x01, 0x20,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42,
                0x42, 0x42, 0x42, 0x42
            )
        )
    }

    init {
        "creating an extension" {
            "by parsing a buffer" {
                "of a one byte extension" {
                    val ext =
                        UnparsedHeaderExtension.fromBuffer(oneByteHeaderExtension.expectedOneByteBuffer, oneByteHeaderExtension.type)
                    should("parse the data correctly") {
                        ext.id shouldBe oneByteHeaderExtension.id
                        ext.data should haveSameContentAs(oneByteHeaderExtension.data)
                        ext.sizeBytes shouldBe oneByteHeaderExtension.expectedOneByteBuffer.limit()
                    }
                    "and then serializing it" {
                        "as a one byte extension" {
                            val buf = ByteBuffer.allocate(ext.sizeBytesAs(HeaderExtensionType.ONE_BYTE_HEADER_EXT))
                            ext.serializeToAs(HeaderExtensionType.ONE_BYTE_HEADER_EXT, buf)
                            should("write the data correctly") {
                                buf should haveSameContentAs(oneByteHeaderExtension.expectedOneByteBuffer)
                            }
                            should("leave the buffer's position after the written data") {
                                buf.position() shouldBe buf.limit()
                            }
                        }
                        "as a two byte extension" {
                            val buf = ByteBuffer.allocate(ext.sizeBytesAs(HeaderExtensionType.TWO_BYTE_HEADER_EXT))
                            ext.serializeToAs(HeaderExtensionType.TWO_BYTE_HEADER_EXT, buf)
                            should("write the data correctly") {
                                buf should haveSameContentAs(oneByteHeaderExtension.expectedTwoByteBuffer)
                            }
                            should("leave the buffer's position after the written data") {
                                buf.position() shouldBe buf.limit()
                            }
                        }
                        "without specifying the type" {
                            should("throw an exception") {
                                shouldThrow<Exception> {
                                    ext.serializeTo(ByteBuffer.allocate(1024))
                                }
                            }
                        }
                    }
                }
                "of a two byte extension (that could fit in one byte)" {
                    val ext =
                        UnparsedHeaderExtension.fromBuffer(twoByteHeaderExtensionThatCouldBeOneByte.expectedTwoByteBuffer, twoByteHeaderExtensionThatCouldBeOneByte.type)
                    should("parse the data correctly") {
                        ext.id shouldBe twoByteHeaderExtensionThatCouldBeOneByte.id
                        ext.data should haveSameContentAs(twoByteHeaderExtensionThatCouldBeOneByte.data)
                        // It could fit as a single byte extension, so the size should match the one-byte size
                        ext.sizeBytes shouldBe twoByteHeaderExtensionThatCouldBeOneByte.expectedOneByteBuffer.limit()
                    }
                    "and then serializing it" {
                        "as a one byte extension" {
                            val buf = ByteBuffer.allocate(ext.sizeBytesAs(HeaderExtensionType.ONE_BYTE_HEADER_EXT))
                            ext.serializeToAs(HeaderExtensionType.ONE_BYTE_HEADER_EXT, buf)
                            should("write the data correctly") {
                                buf should haveSameContentAs(twoByteHeaderExtensionThatCouldBeOneByte.expectedOneByteBuffer)
                            }
                            should("leave the buffer's position after the written data") {
                                buf.position() shouldBe buf.limit()
                            }
                        }
                        "as a two byte extension" {
                            val buf = ByteBuffer.allocate(ext.sizeBytesAs(HeaderExtensionType.TWO_BYTE_HEADER_EXT))
                            ext.serializeToAs(HeaderExtensionType.TWO_BYTE_HEADER_EXT, buf)
                            should("write the data correctly") {
                                buf should haveSameContentAs(twoByteHeaderExtensionThatCouldBeOneByte.expectedTwoByteBuffer)
                            }
                            should("leave the buffer's position after the written data") {
                                buf.position() shouldBe buf.limit()
                            }
                        }
                        "without specifying the type" {
                            should("throw an exception") {
                                shouldThrow<Exception> {
                                    ext.serializeTo(ByteBuffer.allocate(1024))
                                }
                            }
                        }
                    }
                }
                "of a two byte extension" {
                    val ext =
                        UnparsedHeaderExtension.fromBuffer(twoByteHeaderExtension.expectedTwoByteBuffer, twoByteHeaderExtension.type)
                    should("parse the data correctly") {
                        ext.id shouldBe twoByteHeaderExtension.id
                        ext.data should haveSameContentAs(twoByteHeaderExtension.data)
                        // It could fit as a single byte extension, so the size should match the one-byte size
                        ext.sizeBytes shouldBe twoByteHeaderExtension.expectedOneByteBuffer.limit()
                    }
                    "and then serializing it" {
                        "as a one byte extension" {
                            val buf = ByteBuffer.allocate(ext.sizeBytesAs(HeaderExtensionType.ONE_BYTE_HEADER_EXT))
                            should("throw an exception") {
                                shouldThrow<Exception> {
                                    ext.serializeToAs(HeaderExtensionType.ONE_BYTE_HEADER_EXT, buf)
                                }
                            }
                        }
                        "as a two byte extension" {
                            val buf = ByteBuffer.allocate(ext.sizeBytesAs(HeaderExtensionType.TWO_BYTE_HEADER_EXT))
                            ext.serializeToAs(HeaderExtensionType.TWO_BYTE_HEADER_EXT, buf)
                            should("write the data correctly") {
                                buf should haveSameContentAs(twoByteHeaderExtension.expectedTwoByteBuffer)
                            }
                            should("leave the buffer's position after the written data") {
                                buf.position() shouldBe buf.limit()
                            }
                        }
                        "without specifying the type" {
                            should("throw an exception") {
                                shouldThrow<Exception> {
                                    ext.serializeTo(ByteBuffer.allocate(1024))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}