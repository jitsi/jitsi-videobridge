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
import io.kotlintest.matchers.collections.shouldContainExactly
import io.kotlintest.matchers.types.shouldBeInstanceOf
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage.VideoConstraints
import org.jitsi.videobridge.message.BridgeChannelMessage.Companion.parse
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser

class BridgeChannelMessageTest : ShouldSpec() {
    init {
        "serializing" {
            should("encode the type as colibriClass") {
                // Any message will do, this one is just simple
                val message = ServerHelloMessage()

                val parsed = JSONParser().parse(message.toJson())
                parsed.shouldBeInstanceOf<JSONObject>()
                parsed as JSONObject
                val parsedColibriClass = parsed["colibriClass"]
                parsedColibriClass.shouldBeInstanceOf<String>()
                parsedColibriClass as String
                parsedColibriClass shouldBe message.type
            }
        }
        "parsing and serializing a SelectedEndpointsChangedEvent message" {
            val parsed = parse(SELECTED_ENDPOINTS_MESSAGE)
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
                val parsed2 = parse(serialized)
                parsed2.shouldBeInstanceOf<SelectedEndpointsMessage>()
                parsed2 as SelectedEndpointsMessage
                parsed2.selectedEndpoints shouldBe selectedEndpoints
            }
        }
        "parsing an invalid message" {
            shouldThrow<JsonProcessingException> {
                parse("")
            }

            shouldThrow<InvalidMessageTypeException> {
                parse("{}")
            }

            shouldThrow<InvalidMessageTypeException> {
                parse("""{"colibriClass": "invalid-colibri-class" }""")
            }

            "when some of the message-specific fields are missing/invalid" {
                shouldThrow<JsonProcessingException> {
                    parse("""{"colibriClass": "SelectedEndpointsChangedEvent" }""")
                }
                shouldThrow<JsonProcessingException> {
                    parse("""{"colibriClass": "SelectedEndpointsChangedEvent", "selectedEndpoints": 5 }""")
                }
            }
        }
        "serializing and parsing EndpointMessage" {
            val endpointsMessage = EndpointMessage("to_value")
            endpointsMessage.otherFields["other_field1"] = "other_value1"
            endpointsMessage.put("other_field2", 97)

            val json = endpointsMessage.toJson()
            val parsed = parse(json)

            parsed.shouldBeInstanceOf<EndpointMessage>()
            parsed as EndpointMessage
            parsed.from shouldBe null
            parsed.to shouldBe "to_value"
            parsed.otherFields["other_field1"] shouldBe "other_value1"
            parsed.otherFields["other_field2"] shouldBe 97

            "parsing" {
                val parsed2 = parse(ENDPOINT_MESSAGE)
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

            val parsed = parse(message.toJson())

            parsed.shouldBeInstanceOf<ReceiverVideoConstraintsMessage>()
            parsed as ReceiverVideoConstraintsMessage
            parsed.videoConstraints.size shouldBe 2
            parsed.videoConstraints shouldBe constraints
        }

        "serializing and parsing DominantSpeakerMessage" {
            val id = "abc123"
            val original = DominantSpeakerMessage(id)

            val parsed = parse(original.toJson())

            parsed.shouldBeInstanceOf<DominantSpeakerMessage>()
            parsed as DominantSpeakerMessage
            parsed.dominantSpeakerEndpoint shouldBe id
        }

        "serializing and parsing ServerHello" {

            val parsed = parse(ServerHelloMessage().toJson())
            parsed.shouldBeInstanceOf<ServerHelloMessage>()
        }

        "serializing and parsing ClientHello" {

            val parsed = parse(ClientHelloMessage().toJson())
            parsed.shouldBeInstanceOf<ClientHelloMessage>()
        }

        "serializing and parsing EndpointConnectionStatusMessage" {

            val parsed = parse(EndpointConnectionStatusMessage("abcdabcd", true).toJson())
            parsed.shouldBeInstanceOf<EndpointConnectionStatusMessage>()
            parsed as EndpointConnectionStatusMessage

            parsed.endpoint shouldBe "abcdabcd"
            parsed.active shouldBe "true"
        }

        "serializing and parsing ForwardedEndpointsMessage" {
            val forwardedEndpoints = listOf("a", "b", "c")
            val endpointsEnteringLastN = listOf("b", "c")
            val conferenceEndpoints = listOf("a", "b", "c", "d")

            val message = ForwardedEndpointMessage(forwardedEndpoints, endpointsEnteringLastN, conferenceEndpoints)
            val parsed = parse(message.toJson())

            parsed.shouldBeInstanceOf<ForwardedEndpointMessage>()
            parsed as ForwardedEndpointMessage

            parsed.forwardedEndpoints shouldContainExactly forwardedEndpoints
            parsed.endpointsEnteringLastN shouldContainExactly endpointsEnteringLastN
            parsed.conferenceEndpoints shouldContainExactly conferenceEndpoints

            // Make sure the forwardedEndpoints field is serialized as lastNEndpoints as the client (presumably) expects
            val parsedJson = JSONParser().parse(message.toJson())
            parsedJson.shouldBeInstanceOf<JSONObject>()
            parsedJson as JSONObject
            val parsedForwardedEndpoints = parsedJson["lastNEndpoints"]
            parsedForwardedEndpoints.shouldBeInstanceOf<JSONArray>()
            parsedForwardedEndpoints as JSONArray
            parsedForwardedEndpoints.toList() shouldContainExactly forwardedEndpoints
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
