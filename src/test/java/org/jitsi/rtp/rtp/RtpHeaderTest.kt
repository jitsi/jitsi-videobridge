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

package org.jitsi.rtp.rtp

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldContainInOrder
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe

//TODO: sets shouldn't change any other field
class RtpHeaderTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
//    override fun isolationMode(): IsolationMode? = IsolationMode.SingleInstance

    private val headerData = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        //V=2,P=false,X=true,CC=2,M=false,PT=100,SeqNum=16535
        0x92, 0x64, 0x40, 0x97,
        //Timestamp: 3899068446
        0xe8, 0x67, 0x10, 0x1e,
        // SSRC: 2828806853
        0xa8, 0x9c, 0x2a, 0xc5,
        // CSRC 1: 123456
        0x00, 0x01, 0xE2, 0x40,
        // CSRC 2: 45678
        0x00, 0x00, 0xB2, 0x6E,
        // 1 extension
        0xbe, 0xde, 0x00, 0x01,
        0x51, 0x00, 0x02, 0x00
    )

    init {
        "version" {
            "get" {
                should("work correctly") {
                    RtpHeader.getVersion(headerData, 0) shouldBe 2
                }
            }
            "set" {
                should("work correctly") {
                    RtpHeader.setVersion(headerData, 0, 3)
                    RtpHeader.getVersion(headerData, 0) shouldBe 3
                    RtpHeader.setVersion(headerData, 0, 0)
                    RtpHeader.getVersion(headerData, 0) shouldBe 0
                }
            }
        }
        "hasPadding" {
            "get" {
                should("work correctly") {
                    RtpHeader.hasPadding(headerData, 0) shouldBe false
                }
            }
            "set" {
                should("work correctly") {
                    RtpHeader.setPadding(headerData, 0, true)
                    RtpHeader.hasPadding(headerData, 0) shouldBe true
                    RtpHeader.setPadding(headerData, 0, false)
                    RtpHeader.hasPadding(headerData, 0) shouldBe false
                }
            }
        }
        "hasExtensions" {
            "get" {
                should("work correctly") {
                    RtpHeader.hasExtensions(headerData, 0) shouldBe true
                }
            }
            "set" {
                should("work correctly") {
                    RtpHeader.setHasExtensions(headerData, 0, false)
                    RtpHeader.hasExtensions(headerData, 0) shouldBe false
                    RtpHeader.setHasExtensions(headerData, 0, true)
                    RtpHeader.hasExtensions(headerData, 0) shouldBe true
                }
            }
        }
        "csrcCount" {
            "get" {
                should("work correctly") {
                    RtpHeader.getCsrcCount(headerData, 0) shouldBe 2
                }

            }
            "set" {
                should("work correctly") {
                    RtpHeader.setCsrcCount(headerData, 0, 0)
                    RtpHeader.getCsrcCount(headerData, 0) shouldBe 0
                }
            }
        }
        "marker" {
            "get" {
                should("work correctly") {
                    RtpHeader.getMarker(headerData, 0) shouldBe false
                }
            }
            "set" {
                should("work correctly") {
                    RtpHeader.setMarker(headerData, 0, true)
                    RtpHeader.getMarker(headerData, 0) shouldBe true
                    RtpHeader.setMarker(headerData, 0, false)
                    RtpHeader.getMarker(headerData, 0) shouldBe false
                }
            }
        }
        "payloadType" {
            "get" {
                should("work correctly") {
                    RtpHeader.getPayloadType(headerData, 0) shouldBe 100
                }
            }
            "set" {
                should("work correctly") {
                    RtpHeader.setPayloadType(headerData, 0, 111)
                    RtpHeader.getPayloadType(headerData, 0) shouldBe 111
                    RtpHeader.setPayloadType(headerData, 0, 115)
                    RtpHeader.getPayloadType(headerData, 0) shouldBe 115
                }
            }
        }
        "sequenceNumber" {
            "get" {
                should("work correctly") {
                    RtpHeader.getSequenceNumber(headerData, 0) shouldBe 16535
                }
            }
            "set" {
                should("work correctly") {
                    RtpHeader.setSequenceNumber(headerData, 0, 4242)
                    RtpHeader.getSequenceNumber(headerData, 0) shouldBe 4242
                }
            }
        }
        "timestamp" {
            "get" {
                should("work correctly") {
                    RtpHeader.getTimestamp(headerData, 0) shouldBe 3899068446L
                }

            }
            "set" {
                should("work correctly") {
                    RtpHeader.setTimestamp(headerData, 0, 3899064242)
                    RtpHeader.getTimestamp(headerData, 0) shouldBe 3899064242
                }
            }
        }
        "ssrc" {
            "get" {
                should("work correctly") {
                    RtpHeader.getSsrc(headerData, 0) shouldBe 2828806853
                }
            }
            "set" {
                should("work correctly") {
                    RtpHeader.setSsrc(headerData, 0, 424242)
                    RtpHeader.getSsrc(headerData, 0) shouldBe 424242
                }
            }
        }
        "csrcs" {
            "get" {
                should("work correctly") {
                    RtpHeader.getCsrcs(headerData, 0) shouldContainInOrder(listOf<Long>(123456, 45678))
                }
            }
            "set" {
                should("work correctly") {
                    RtpHeader.setCsrcs(headerData, 0, listOf<Long>(2468, 1357))
                    RtpHeader.getCsrcs(headerData, 0) shouldContainInOrder(listOf<Long>(2468, 1357))

                }

            }
        }
    }
}