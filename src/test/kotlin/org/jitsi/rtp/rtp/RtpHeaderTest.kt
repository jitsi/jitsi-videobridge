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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import org.jitsi.rtp.extensions.bytearray.byteArrayOf

// TODO: sets shouldn't change any other field
class RtpHeaderTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
//    override fun isolationMode(): IsolationMode? = IsolationMode.SingleInstance

    private val headerData = byteArrayOf(
        // V=2,P=false,X=true,CC=2,M=false,PT=100,SeqNum=16535
        0x92, 0x64, 0x40, 0x97,
        // Timestamp: 3899068446
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
        context("version") {
            context("get") {
                should("work correctly") {
                    RtpHeader.getVersion(headerData, 0) shouldBe 2
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setVersion(headerData, 0, 3)
                    RtpHeader.getVersion(headerData, 0) shouldBe 3
                    RtpHeader.setVersion(headerData, 0, 0)
                    RtpHeader.getVersion(headerData, 0) shouldBe 0
                }
            }
        }
        context("hasPadding") {
            context("get") {
                should("work correctly") {
                    RtpHeader.hasPadding(headerData, 0) shouldBe false
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setPadding(headerData, 0, true)
                    RtpHeader.hasPadding(headerData, 0) shouldBe true
                    RtpHeader.setPadding(headerData, 0, false)
                    RtpHeader.hasPadding(headerData, 0) shouldBe false
                }
            }
        }
        context("hasExtensions") {
            context("get") {
                should("work correctly") {
                    RtpHeader.hasExtensions(headerData, 0) shouldBe true
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setHasExtensions(headerData, 0, false)
                    RtpHeader.hasExtensions(headerData, 0) shouldBe false
                    RtpHeader.setHasExtensions(headerData, 0, true)
                    RtpHeader.hasExtensions(headerData, 0) shouldBe true
                }
            }
        }
        context("csrcCount") {
            context("get") {
                should("work correctly") {
                    RtpHeader.getCsrcCount(headerData, 0) shouldBe 2
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setCsrcCount(headerData, 0, 0)
                    RtpHeader.getCsrcCount(headerData, 0) shouldBe 0
                }
            }
        }
        context("marker") {
            context("get") {
                should("work correctly") {
                    RtpHeader.getMarker(headerData, 0) shouldBe false
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setMarker(headerData, 0, true)
                    RtpHeader.getMarker(headerData, 0) shouldBe true
                    RtpHeader.setMarker(headerData, 0, false)
                    RtpHeader.getMarker(headerData, 0) shouldBe false
                }
                should("not clobber payload type") {
                    val origPt = RtpHeader.getPayloadType(headerData, 0)
                    RtpHeader.setMarker(headerData, 0, true)
                    RtpHeader.getMarker(headerData, 0) shouldBe true
                    RtpHeader.getPayloadType(headerData, 0) shouldBe origPt

                    RtpHeader.setMarker(headerData, 0, false)
                    RtpHeader.getMarker(headerData, 0) shouldBe false
                    RtpHeader.getPayloadType(headerData, 0) shouldBe origPt
                }
            }
        }
        context("payloadType") {
            context("get") {
                should("work correctly") {
                    RtpHeader.getPayloadType(headerData, 0) shouldBe 100
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setPayloadType(headerData, 0, 111)
                    RtpHeader.getPayloadType(headerData, 0) shouldBe 111
                    RtpHeader.setPayloadType(headerData, 0, 115)
                    RtpHeader.getPayloadType(headerData, 0) shouldBe 115
                }
            }
        }
        context("sequenceNumber") {
            context("get") {
                should("work correctly") {
                    RtpHeader.getSequenceNumber(headerData, 0) shouldBe 16535
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setSequenceNumber(headerData, 0, 4242)
                    RtpHeader.getSequenceNumber(headerData, 0) shouldBe 4242
                }
            }
        }
        context("timestamp") {
            context("get") {
                should("work correctly") {
                    RtpHeader.getTimestamp(headerData, 0) shouldBe 3899068446L
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setTimestamp(headerData, 0, 3899064242)
                    RtpHeader.getTimestamp(headerData, 0) shouldBe 3899064242
                }
            }
        }
        context("ssrc") {
            context("get") {
                should("work correctly") {
                    RtpHeader.getSsrc(headerData, 0) shouldBe 2828806853
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setSsrc(headerData, 0, 424242)
                    RtpHeader.getSsrc(headerData, 0) shouldBe 424242
                }
            }
        }
        context("csrcs") {
            context("get") {
                should("work correctly") {
                    RtpHeader.getCsrcs(headerData, 0) shouldContainInOrder(listOf<Long>(123456, 45678))
                }
            }
            context("set") {
                should("work correctly") {
                    RtpHeader.setCsrcs(headerData, 0, listOf<Long>(2468, 1357))
                    RtpHeader.getCsrcs(headerData, 0) shouldContainInOrder(listOf<Long>(2468, 1357))
                }
            }
        }
        context("total length") {
            should("be correct") {
                RtpHeader.getTotalLength(headerData, 0) shouldBe 28
            }
        }
    }
}
