/*
 * Copyright @ 2020-Present 8x8, Inc
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
package org.jitsi.videobridge.message

import com.fasterxml.jackson.core.JsonProcessingException
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.matchers.types.shouldBeInstanceOf
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage.VideoConstraints

class BridgeChannelMessageTest : ShouldSpec() {
    init {
        "parsing and serializing a SelectedEndpointsChangedEvent message" {
            val parsed = BridgeChannelMessage.parse(SELECTED_ENDPOINTS_MESSAGE)
            should("parse to the correct type") {
                parsed.shouldBeInstanceOf<SelectedEndpointsMessage>()
            }
            should("parse the list of endpoints correctly") {
                parsed as SelectedEndpointsMessage
                parsed.selectedEndpoints shouldBe listOf("abcdabcd", "12341234")
            }

            should("serialize and de-seriealize correctly") {
                val selectedEndpoints = listOf("abcdabcd", "12341234")
                val serialized = SelectedEndpointsMessage(selectedEndpoints).toJson()
                val parsed2 = BridgeChannelMessage.parse(serialized)
                parsed2.shouldBeInstanceOf<SelectedEndpointsMessage>()
                parsed2 as SelectedEndpointsMessage
                parsed2.selectedEndpoints shouldBe selectedEndpoints
            }
        }
        "parsing an invalid message" {
            shouldThrow<JsonProcessingException> {
                BridgeChannelMessage.parse("")
            }

            shouldThrow<InvalidMessageTypeException> {
                BridgeChannelMessage.parse("{}")
            }

            shouldThrow<InvalidMessageTypeException> {
                BridgeChannelMessage.parse("""{"colibriClass": "invalid-colibri-class" }""")
            }

            "when some of the message-specific fields are missing/invalid" {
                shouldThrow<JsonProcessingException> {
                    BridgeChannelMessage.parse("""{"colibriClass": "SelectedEndpointsChangedEvent" }""")
                }
                shouldThrow<JsonProcessingException> {
                    BridgeChannelMessage.parse("""{"colibriClass": "SelectedEndpointsChangedEvent", "selectedEndpoints": 5 }""")
                }
            }
        }
        "serializing and parsing EndpointMessage" {
            val endpointsMessage = EndpointMessage("to_value")
            endpointsMessage.otherFields["other_field1"] = "other_value1"
            endpointsMessage.put("other_field2", 97)

            val json = endpointsMessage.toJson()
            val parsed = BridgeChannelMessage.parse(json)

            parsed.shouldBeInstanceOf<EndpointMessage>()
            parsed as EndpointMessage
            parsed.from shouldBe null
            parsed.to shouldBe "to_value"
            parsed.otherFields["other_field1"] shouldBe "other_value1"
            parsed.otherFields["other_field2"] shouldBe 97

            "parsing" {
                val parsed2 = BridgeChannelMessage.parse(ENDPOINT_MESSAGE)
                parsed2 as EndpointMessage
                parsed2.from shouldBe null
                parsed2.to shouldBe "to_value"
                parsed2.otherFields["other_field1"] shouldBe "other_value1"
                parsed2.otherFields["other_field2"] shouldBe 97
            }
        }

        "serializing and parsing ReceiverVideoConstraintsChangedEvent" {
            val constraints = listOf(
                    VideoConstraints("abcdabcd", 180),
                    VideoConstraints("12341234", 360))
            val message = ReceiverVideoConstraintsMessage(constraints)

            val parsed = BridgeChannelMessage.parse(message.toJson())

            parsed.shouldBeInstanceOf<ReceiverVideoConstraintsMessage>()
            parsed as ReceiverVideoConstraintsMessage
            parsed.videoConstraints.size shouldBe 2
            parsed.videoConstraints shouldBe constraints
        }
    }

    companion object {
        const val SELECTED_ENDPOINTS_MESSAGE = """
            {
              "colibriClass": "SelectedEndpointsChangedEvent",
              "selectedEndpoints": [ "abcdabcd", "12341234" ]
            }
        """

        const val ENDPOINT_MESSAGE = """
            {
              "colibriClass": "EndpointMessage",
              "to": "to_value",
              "other_field1": "other_value1",
              "other_field2": 97
            }
        """
    }
}
