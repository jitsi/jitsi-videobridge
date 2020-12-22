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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.message.BridgeChannelMessage.Companion.parse
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser

@Suppress("BlockingMethodInNonBlockingContext")
class BridgeChannelMessageTest : ShouldSpec() {
    init {
        context("serializing") {
            should("encode the type as colibriClass") {
                // Any message will do, this one is just simple
                val message = ClientHelloMessage()

                val parsed = JSONParser().parse(message.toJson())
                parsed.shouldBeInstanceOf<JSONObject>()
                parsed as JSONObject
                val parsedColibriClass = parsed["colibriClass"]
                parsedColibriClass.shouldBeInstanceOf<String>()
                parsedColibriClass as String
                parsedColibriClass shouldBe message.type
            }
        }
        context("parsing and serializing a SelectedEndpointsChangedEvent message") {
            val parsed = parse(SELECTED_ENDPOINTS_MESSAGE)
            should("parse to the correct type") {
                parsed.shouldBeInstanceOf<SelectedEndpointsMessage>()
            }
            should("parse the list of endpoints correctly") {
                parsed as SelectedEndpointsMessage
                parsed.selectedEndpoints shouldBe listOf("abcdabcd", "12341234")
            }

            should("serialize and de-serialize correctly") {
                val selectedEndpoints = listOf("abcdabcd", "12341234")
                val serialized = SelectedEndpointsMessage(selectedEndpoints).toJson()
                val parsed2 = parse(serialized)
                parsed2.shouldBeInstanceOf<SelectedEndpointsMessage>()
                parsed2 as SelectedEndpointsMessage
                parsed2.selectedEndpoints shouldBe selectedEndpoints
            }
        }
        context("parsing an invalid message") {
            shouldThrow<JsonProcessingException> {
                parse("{invalid json")
            }

            shouldThrow<JsonProcessingException> {
                parse("")
            }

            shouldThrow<InvalidTypeIdException> {
                parse("{}")
            }

            shouldThrow<InvalidTypeIdException> {
                parse("""{"colibriClass": "invalid-colibri-class" }""")
            }

            context("when some of the message-specific fields are missing/invalid") {
                shouldThrow<JsonProcessingException> {
                    parse("""{"colibriClass": "SelectedEndpointsChangedEvent" }""")
                }
                shouldThrow<JsonProcessingException> {
                    parse("""{"colibriClass": "SelectedEndpointsChangedEvent", "selectedEndpoints": 5 }""")
                }
            }
        }
        context("serializing and parsing EndpointMessage") {
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

            context("parsing") {
                val parsed2 = parse(ENDPOINT_MESSAGE)
                parsed2 as EndpointMessage
                parsed2.from shouldBe null
                parsed2.to shouldBe "to_value"
                parsed2.otherFields["other_field1"] shouldBe "other_value1"
                parsed2.otherFields["other_field2"] shouldBe 97
            }
        }

        context("serializing and parsing DominantSpeakerMessage") {
            val id = "abc123"
            val original = DominantSpeakerMessage(id)

            val parsed = parse(original.toJson())

            parsed.shouldBeInstanceOf<DominantSpeakerMessage>()
            parsed as DominantSpeakerMessage
            parsed.dominantSpeakerEndpoint shouldBe id
        }

        context("serializing and parsing ServerHello") {
            context("without a version") {
                val parsed = parse(ServerHelloMessage().toJson())
                parsed.shouldBeInstanceOf<ServerHelloMessage>()
                parsed as ServerHelloMessage
                parsed.version shouldBe null
            }
            context("with a version") {
                val message = ServerHelloMessage("v")

                val parsed = parse(message.toJson())
                parsed.shouldBeInstanceOf<ServerHelloMessage>()
                parsed as ServerHelloMessage
                parsed.version shouldBe "v"
            }
        }

        context("serializing and parsing ClientHello") {

            val parsed = parse(ClientHelloMessage().toJson())
            parsed.shouldBeInstanceOf<ClientHelloMessage>()
        }

        context("serializing and parsing EndpointConnectionStatusMessage") {

            val parsed = parse(EndpointConnectionStatusMessage("abcdabcd", true).toJson())
            parsed.shouldBeInstanceOf<EndpointConnectionStatusMessage>()
            parsed as EndpointConnectionStatusMessage

            parsed.endpoint shouldBe "abcdabcd"
            parsed.active shouldBe "true"
        }

        context("serializing and parsing ForwardedEndpointsMessage") {
            val forwardedEndpoints = setOf("a", "b", "c")

            val message = ForwardedEndpointsMessage(forwardedEndpoints)
            val parsed = parse(message.toJson())

            parsed.shouldBeInstanceOf<ForwardedEndpointsMessage>()
            parsed as ForwardedEndpointsMessage

            parsed.forwardedEndpoints shouldContainExactly forwardedEndpoints

            // Make sure the forwardedEndpoints field is serialized as lastNEndpoints as the client (presumably) expects
            val parsedJson = JSONParser().parse(message.toJson())
            parsedJson.shouldBeInstanceOf<JSONObject>()
            parsedJson as JSONObject
            val parsedForwardedEndpoints = parsedJson["lastNEndpoints"]
            parsedForwardedEndpoints.shouldBeInstanceOf<JSONArray>()
            parsedForwardedEndpoints as JSONArray
            parsedForwardedEndpoints.toList() shouldContainExactly forwardedEndpoints
        }

        context("serializing and parsing VideoConstraints") {
            val videoConstraints: VideoConstraints = jacksonObjectMapper().readValue(VIDEO_CONSTRAINTS)
            videoConstraints.maxHeight shouldBe 1080
            videoConstraints.maxFrameRate shouldBe 15.0
        }

        context("and SenderVideoConstraintsMessage") {
            val senderVideoConstraintsMessage = SenderVideoConstraintsMessage(1080)
            val parsed = parse(senderVideoConstraintsMessage.toJson())

            parsed.shouldBeInstanceOf<SenderVideoConstraintsMessage>()
            parsed as SenderVideoConstraintsMessage

            parsed.videoConstraints.idealHeight shouldBe 1080
        }

        context("serializing and parsing AddReceiver") {
            val message = AddReceiverMessage("bridge1", "abcdabcd", VideoConstraints(360))
            val parsed = parse(message.toJson())

            parsed.shouldBeInstanceOf<AddReceiverMessage>()
            parsed as AddReceiverMessage
            parsed.bridgeId shouldBe "bridge1"
            parsed.endpointId shouldBe "abcdabcd"
            parsed.videoConstraints shouldBe VideoConstraints(360)
        }

        context("serializing and parsing RemoveReceiver") {
            val message = RemoveReceiverMessage("bridge1", "abcdabcd")
            val parsed = parse(message.toJson())

            parsed.shouldBeInstanceOf<RemoveReceiverMessage>()
            parsed as RemoveReceiverMessage
            parsed.bridgeId shouldBe "bridge1"
            parsed.endpointId shouldBe "abcdabcd"
        }

        context("Parsing BandwidthAllocationSettingsMessage") {
            context("With all fields present") {
                val parsed = parse(BANDWIDTH_ALLOCATION_SETTINGS)

                parsed.shouldBeInstanceOf<BandwidthAllocationSettingsMessage>()
                parsed as BandwidthAllocationSettingsMessage
                parsed.lastN shouldBe 3
                parsed.onStageEndpoints shouldBe listOf("onstage1", "onstage2")
                parsed.selectedEndpoints shouldBe listOf("selected1", "selected2")
                parsed.defaultConstraints shouldBe VideoConstraints(0)
                val constraints = parsed.constraints
                constraints.shouldNotBeNull()
                constraints.size shouldBe 3
                constraints["epOnStage"] shouldBe VideoConstraints(720)
                constraints["epThumbnail1"] shouldBe VideoConstraints(180)
                constraints["epThumbnail2"] shouldBe VideoConstraints(180, 30.0)
            }

            context("With fields missing") {
                val parsed = parse(BANDWIDTH_ALLOCATION_SETTINGS_EMPTY)
                parsed.shouldBeInstanceOf<BandwidthAllocationSettingsMessage>()
                parsed as BandwidthAllocationSettingsMessage
                parsed.lastN shouldBe null
                parsed.onStageEndpoints shouldBe null
                parsed.selectedEndpoints shouldBe null
                parsed.defaultConstraints shouldBe null
                parsed.constraints shouldBe null
            }
        }
    }

    private fun testSerializePerformance() {
        val m = DominantSpeakerMessage("x")
        val times = 1_000_000

        fun toJsonJackson(m: DominantSpeakerMessage): String = ObjectMapper().writeValueAsString(m)
        fun toJsonJsonSimple(m: DominantSpeakerMessage) = JSONObject().apply {
            this["dominantSpeakerEndpoint"] = m.dominantSpeakerEndpoint
        }.toJSONString()
        fun toJsonStringConcat(m: DominantSpeakerMessage) =
            "{\"colibriClass\":\"DominantSpeakerEndpointChangeEvent\",\"dominantSpeakerEndpoint\":\"" +
                m.dominantSpeakerEndpoint + "\"}"
        fun toJsonStringTemplate(m: DominantSpeakerMessage) =
            "{\"colibriClass\":\"${DominantSpeakerMessage.TYPE}\"," +
                "\"dominantSpeakerEndpoint\":\"${m.dominantSpeakerEndpoint}\"}"
        fun toJsonRawStringTemplate(m: DominantSpeakerMessage) = """
            {"colibriClass":"${DominantSpeakerMessage.TYPE}",
             "dominantSpeakerEndpoint":"${m.dominantSpeakerEndpoint}"}
         """

        fun runTest(f: (DominantSpeakerMessage) -> String): Long {
            val start = System.currentTimeMillis()
            for (i in 0..times) {
                m.dominantSpeakerEndpoint = i.toString()
                f(m)
            }
            val end = System.currentTimeMillis()

            return end - start
        }

        System.err.println("Times=$times")
        System.err.println("Jackson: ${runTest { toJsonJackson(it) } }")
        System.err.println("Json-simple: ${runTest { toJsonJsonSimple(it) } }")
        System.err.println("String concat: ${runTest { toJsonStringConcat(it) } }")
        System.err.println("String template: ${runTest { toJsonStringTemplate(it) } }")
        System.err.println("Raw string template: ${runTest { toJsonRawStringTemplate(it) } }")
        System.err.println("Raw string template (trim): ${runTest { toJsonRawStringTemplate(it).trimMargin() } }")
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

        const val VIDEO_CONSTRAINTS = """
            {
                "maxHeight": 1080,
                "maxFrameRate": 15.0
            }
        """

        const val BANDWIDTH_ALLOCATION_SETTINGS_EMPTY = """
            {
              "colibriClass": "BandwidthAllocationSettings"
            }
        """
        const val BANDWIDTH_ALLOCATION_SETTINGS = """
            {
              "colibriClass": "BandwidthAllocationSettings",
              "lastN": 3,
              "selectedEndpoints": [ "selected1", "selected2" ],
              "onStageEndpoints": [ "onstage1", "onstage2" ],
              "defaultConstraints": { "maxHeight": 0 },
              "constraints": {
                "epOnStage": { "maxHeight": 720 },
                "epThumbnail1": { "maxHeight": 180 },
                "epThumbnail2": { "maxHeight": 180, "maxFrameRate": 30 }
              }
            }
        """
    }
}
