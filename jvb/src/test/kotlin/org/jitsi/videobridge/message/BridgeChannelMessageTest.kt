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

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotInclude
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.nlj.VideoType
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.message.BridgeChannelMessage.Companion.parse

@Suppress("BlockingMethodInNonBlockingContext")
class BridgeChannelMessageTest : ShouldSpec() {
    init {
        context("serializing") {
            should("encode the type as colibriClass") {
                // Any message will do, this one is just simple
                val message = ClientHelloMessage()

                val parsed = jacksonObjectMapper().readTree(message.toJson())
                parsed.shouldBeInstanceOf<ObjectNode>()
                val parsedColibriClass = parsed["colibriClass"]
                parsedColibriClass.shouldBeInstanceOf<TextNode>()
                parsedColibriClass.asText() shouldBe ClientHelloMessage.TYPE
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
            shouldThrow<JsonParseException> {
                parse(
                    """
                {
                  "colibriClass": "EndpointStats",
                  "colibriClass": "duplicate"
                }
                    """.trimIndent()
                )
            }
            shouldThrow<JsonParseException> {
                parse(
                    """
                {
                  "colibriClass": "EndpointStats",
                  "to": "a",
                  "to": "b"
                }
                    """.trimIndent()
                )
            }
            shouldThrow<JsonParseException> {
                parse(
                    """
                {
                  "colibriClass": "EndpointStats",
                  "from": "a",
                  "from": "b"
                }
                    """.trimIndent()
                )
            }
            shouldThrow<JsonParseException> {
                parse(
                    """
                {
                  "colibriClass": "EndpointStats",
                  "non-defined-prop": "a",
                  "non-defined-prop": "b"
                }
                    """.trimIndent()
                )
            }

            context("when some of the message-specific fields are missing/invalid") {
                shouldThrow<JsonProcessingException> {
                    // Missing dominantSpeakerEndpoint field
                    parse("""{"colibriClass": "DominantSpeakerEndpointChangeEvent" }""")
                }
                shouldThrow<JsonProcessingException> {
                    // dominantSpeakerEndpoint has the wrong type
                    parse("""{"colibriClass": "DominantSpeakerEndpointChangeEvent", "dominantSpeakerEndpoint": [5] }""")
                }
            }
        }
        context("serializing and parsing EndpointMessage") {
            val endpointsMessage = EndpointMessage("to_value")
            endpointsMessage.otherFields["other_field1"] = "other_value1"
            endpointsMessage.put("other_field2", 97)
            endpointsMessage.put("other_null", null)

            val json = endpointsMessage.toJson()
            // Make sure we don't mistakenly serialize the "broadcast" flag.
            json shouldNotInclude "broadcast"
            // Make sure we don't mistakenly serialize the "type".
            json shouldNotInclude """
                "type":
            """.trimIndent()
            val parsed = parse(json)

            parsed.shouldBeInstanceOf<EndpointMessage>()
            parsed.from shouldBe null
            parsed.to shouldBe "to_value"
            parsed.otherFields["other_field1"] shouldBe "other_value1"
            parsed.otherFields["other_field2"] shouldBe 97
            parsed.otherFields["other_null"] shouldBe null
            parsed.otherFields.containsKey("other_null") shouldBe true
            parsed.otherFields.containsKey("nonexistent") shouldBe false

            endpointsMessage.from = "new"
            (parse(endpointsMessage.toJson()) as EndpointMessage).from shouldBe "new"

            context("parsing") {
                val parsed2 = parse(ENDPOINT_MESSAGE)
                parsed2 as EndpointMessage
                parsed2.from shouldBe null
                parsed2.to shouldBe "to_value"
                parsed2.otherFields["other_field1"] shouldBe "other_value1"
                parsed2.otherFields["other_field2"] shouldBe 97
                parsed2.otherFields.containsKey("other_null") shouldBe true
                parsed2.otherFields.containsKey("nonexistent") shouldBe false
            }

            // EndpointMessage deserializes via a constructor creator ("to"). jackson-databind 2.18.x has a
            // regression that drops @JsonAnySetter fields for creator-based types, which stripped msgPayload
            // from forwarded EndpointMessages. This guards the round-trip (parse -> set from -> serialize).
            should("preserve any-fields when forwarding (msgPayload is not stripped)") {
                val payload =
                    """{"colibriClass":"EndpointMessage","msgPayload":{"type":"face-box","faceBox":{"left":33,"right":64,"width":31}},"to":""}"""
                val parsed3 = parse(payload) as EndpointMessage
                parsed3.to shouldBe ""
                withClue("otherFields=${parsed3.otherFields}") {
                    parsed3.otherFields.containsKey("msgPayload") shouldBe true
                }
                parsed3.from = "3b213301"
                withClue("json=${parsed3.toJson()}") {
                    parsed3.toJson() shouldContain "msgPayload"
                }
            }
        }

        context("serializing and parsing DominantSpeakerMessage") {
            val previousSpeakers = listOf("p1", "p2")
            val original = DominantSpeakerMessage("d", previousSpeakers)

            val parsed = parse(original.toJson())

            parsed.shouldBeInstanceOf<DominantSpeakerMessage>()
            parsed.dominantSpeakerEndpoint shouldBe "d"
            parsed.previousSpeakers shouldBe listOf("p1", "p2")
        }

        context("serializing and parsing ServerHello") {
            context("without a version") {
                val parsed = parse(ServerHelloMessage().toJson())
                parsed.shouldBeInstanceOf<ServerHelloMessage>()
                parsed.version shouldBe null
            }
            context("with a version") {
                val message = ServerHelloMessage("v")

                val parsed = parse(message.toJson())
                parsed.shouldBeInstanceOf<ServerHelloMessage>()
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

            parsed.endpoint shouldBe "abcdabcd"
            parsed.active shouldBe "true"
        }

        context("serializing and parsing ForwardedSourcesMessage") {
            val forwardedSources = setOf("s1", "s2", "s3")

            val message = ForwardedSourcesMessage(forwardedSources)
            val parsed = parse(message.toJson())

            parsed.shouldBeInstanceOf<ForwardedSourcesMessage>()

            parsed.forwardedSources shouldContainExactly forwardedSources
        }

        context("serializing and parsing VideoConstraints") {
            val videoConstraints: VideoConstraints = jacksonObjectMapper().readValue(VIDEO_CONSTRAINTS)
            videoConstraints.maxHeight shouldBe 1080
            videoConstraints.maxFrameRate shouldBe 15.0
        }

        context("serializing and parsing SenderSourceConstraintsMessage") {
            val senderSourceConstraintsMessage = SenderSourceConstraintsMessage("s1", 1080)
            val parsed = parse(senderSourceConstraintsMessage.toJson())

            parsed.shouldBeInstanceOf<SenderSourceConstraintsMessage>()

            parsed.sourceName shouldBe "s1"
            parsed.maxHeight shouldBe 1080
        }

        context("serializing and parsing AddReceiver") {
            context("with source names") {
                val message = AddReceiverMessage("bridge1", "s1", VideoConstraints(360))
                val parsed = parse(message.toJson())

                parsed.shouldBeInstanceOf<AddReceiverMessage>()
                parsed.bridgeId shouldBe "bridge1"
                parsed.sourceName shouldBe "s1"
                parsed.videoConstraints shouldBe VideoConstraints(360)
            }
            context("without source names") {
                val message = AddReceiverMessage("bridge1", "source1", VideoConstraints(360))
                val parsed = parse(message.toJson())

                parsed.shouldBeInstanceOf<AddReceiverMessage>()
                parsed.bridgeId shouldBe "bridge1"
                parsed.sourceName shouldBe "source1"
                parsed.videoConstraints shouldBe VideoConstraints(360)
            }
        }

        context("serializing and parsing RemoveReceiver") {
            val message = RemoveReceiverMessage("bridge1", "abcdabcd")
            val parsed = parse(message.toJson())

            parsed.shouldBeInstanceOf<RemoveReceiverMessage>()
            parsed.bridgeId shouldBe "bridge1"
            parsed.endpointId shouldBe "abcdabcd"
        }

        context("serializing and parsing VideoType") {
            val videoTypeMessage = VideoTypeMessage(VideoType.DESKTOP)
            videoTypeMessage.videoType shouldBe VideoType.DESKTOP
            parse(videoTypeMessage.toJson()).apply {
                shouldBeInstanceOf<VideoTypeMessage>()
                videoType shouldBe VideoType.DESKTOP
            }

            listOf("none", "NONE", "None", "nOnE").forEach {
                val jsonString = """
                    {
                        "colibriClass" : "VideoTypeMessage",
                        "videoType" : "$it"
                    }
                    """
                parse(jsonString).apply {
                    shouldBeInstanceOf<VideoTypeMessage>()
                    videoType shouldBe VideoType.NONE
                }
            }
            val jsonString = """
                    {
                        "colibriClass" : "VideoTypeMessage",
                        "videoType" : "desktop_high_fps"
                    }
                    """
            parse(jsonString).apply {
                shouldBeInstanceOf<VideoTypeMessage>()
                videoType shouldBe VideoType.DESKTOP_HIGH_FPS
            }
        }

        context("serializing and parsing SourceVideoType") {
            val testSourceName = "source1234"
            val srcVideoTypeMessage = SourceVideoTypeMessage(VideoType.DESKTOP, testSourceName)
            srcVideoTypeMessage.videoType shouldBe VideoType.DESKTOP
            parse(srcVideoTypeMessage.toJson()).apply {
                shouldBeInstanceOf<SourceVideoTypeMessage>()
                videoType shouldBe VideoType.DESKTOP
                sourceName shouldBe testSourceName
            }

            listOf("none", "NONE", "None", "nOnE").forEach {
                val jsonString = """
                    {
                        "colibriClass" : "SourceVideoTypeMessage",
                        "sourceName": "$testSourceName",
                        "videoType" : "$it"
                    }
                    """
                parse(jsonString).apply {
                    shouldBeInstanceOf<SourceVideoTypeMessage>()
                    videoType shouldBe VideoType.NONE
                    sourceName shouldBe testSourceName
                }
            }
            val jsonString = """
                    {
                        "colibriClass" : "SourceVideoTypeMessage",
                        "sourceName" : "source1234",
                        "videoType" : "desktop_high_fps"
                    }
                    """
            parse(jsonString).apply {
                shouldBeInstanceOf<SourceVideoTypeMessage>()
                videoType shouldBe VideoType.DESKTOP_HIGH_FPS
                sourceName shouldBe testSourceName
            }
        }

        context("serializing and parsing VideoSourceMap") {
            val source1 = "source1234"
            val owner1 = "endpoint1"
            val ssrc1 = 12345L
            val rtxSsrc1 = 45678L

            val source2 = "source5678"
            val owner2 = "endpoint2"
            val ssrc2 = 87654L
            val rtxSsrc2 = 98765L

            val videoSourcesMapMessage = VideoSourcesMap(
                listOf(
                    VideoSourceMapping(source1, owner1, ssrc1, rtxSsrc1, VideoType.CAMERA),
                    VideoSourceMapping(source2, owner2, ssrc2, rtxSsrc2, VideoType.DESKTOP)
                )
            )

            parse(videoSourcesMapMessage.toJson()).apply {
                shouldBeInstanceOf<VideoSourcesMap>()
                mappedSources.size shouldBe 2
                mappedSources shouldContainExactly
                    listOf(
                        VideoSourceMapping(source1, owner1, ssrc1, rtxSsrc1, VideoType.CAMERA),
                        VideoSourceMapping(source2, owner2, ssrc2, rtxSsrc2, VideoType.DESKTOP)
                    )
            }

            val jsonString = """
                {"colibriClass":"VideoSourcesMap",
                 "mappedSources":[{"source":"source1234","owner":"endpoint1","ssrc":12345,"rtx":45678,"videoType":"CAMERA"},
                                  {"source":"source5678","owner":"endpoint2","ssrc":87654,"rtx":98765,"videoType":"DESKTOP"}
                                 ]
                }
            """.trimIndent()

            parse(jsonString).apply {
                shouldBeInstanceOf<VideoSourcesMap>()
                mappedSources.size shouldBe 2
                mappedSources shouldContainExactly
                    listOf(
                        VideoSourceMapping(source1, owner1, ssrc1, rtxSsrc1, VideoType.CAMERA),
                        VideoSourceMapping(source2, owner2, ssrc2, rtxSsrc2, VideoType.DESKTOP)
                    )
            }
        }

        context("serializing and parsing AudioSourceMap") {
            val source1 = "source1234-a"
            val owner1 = "endpoint1"
            val ssrc1 = 23456L

            val source2 = "source5678-a"
            val owner2 = "endpoint2"
            val ssrc2 = 98765L

            val audioSourcesMapMessage = AudioSourcesMap(
                listOf(
                    AudioSourceMapping(source1, owner1, ssrc1),
                    AudioSourceMapping(source2, owner2, ssrc2)
                )
            )

            parse(audioSourcesMapMessage.toJson()).apply {
                shouldBeInstanceOf<AudioSourcesMap>()
                mappedSources.size shouldBe 2
                mappedSources shouldContainExactly
                    listOf(
                        AudioSourceMapping(source1, owner1, ssrc1),
                        AudioSourceMapping(source2, owner2, ssrc2)
                    )
            }

            val jsonString = """
                {"colibriClass":"AudioSourcesMap",
                 "mappedSources":[{"source":"source1234-a","owner":"endpoint1","ssrc":23456},
                                  {"source":"source5678-a","owner":"endpoint2","ssrc":98765}
                                 ]
                }
            """.trimIndent()

            parse(jsonString).apply {
                shouldBeInstanceOf<AudioSourcesMap>()
                mappedSources.size shouldBe 2
                mappedSources shouldContainExactly
                    listOf(
                        AudioSourceMapping(source1, owner1, ssrc1),
                        AudioSourceMapping(source2, owner2, ssrc2)
                    )
            }
        }

        context("Parsing ReceiverVideoConstraints") {
            context("With all fields present") {
                val parsed = parse(RECEIVER_VIDEO_CONSTRAINTS)

                parsed.shouldBeInstanceOf<ReceiverVideoConstraintsMessage>()
                parsed.lastN shouldBe 3
                parsed.defaultConstraints shouldBe VideoConstraints(0)
                val constraints = parsed.constraints
                constraints.shouldNotBeNull()
                constraints.size shouldBe 3
                constraints["epOnStage"] shouldBe VideoConstraints(720)
                constraints["epThumbnail1"] shouldBe VideoConstraints(180)
                constraints["epThumbnail2"] shouldBe VideoConstraints(180, 30.0)
            }

            context("With fields missing") {
                val parsed = parse(RECEIVER_VIDEO_CONSTRAINTS_EMPTY)
                parsed.shouldBeInstanceOf<ReceiverVideoConstraintsMessage>()
                parsed.lastN shouldBe null
                parsed.defaultConstraints shouldBe null
                parsed.constraints shouldBe null
            }
        }

        context("serializing and parsing ReceiverAudioSubscriptionMessage") {
            context("all subscription") {
                val message = ReceiverAudioSubscriptionMessage(all = true)
                val parsed = parse(message.toJson())

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe true
                parsed.include shouldBe emptyList()
                parsed.exclude shouldBe emptyList()
            }

            context("none subscription") {
                val message = ReceiverAudioSubscriptionMessage(all = false)
                val parsed = parse(message.toJson())

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe false
                parsed.include shouldBe emptyList()
                parsed.exclude shouldBe emptyList()
            }

            context("subscription with include list") {
                val includeList = listOf("endpoint1", "endpoint2", "endpoint3")
                val message = ReceiverAudioSubscriptionMessage(all = false, include = includeList)
                val parsed = parse(message.toJson())

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe false
                parsed.include shouldContainExactly includeList
                parsed.exclude shouldBe emptyList()
            }

            context("subscription with exclude list") {
                val excludeList = listOf("endpoint4", "endpoint5")
                val message = ReceiverAudioSubscriptionMessage(all = true, exclude = excludeList)
                val parsed = parse(message.toJson())

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe true
                parsed.exclude shouldContainExactly excludeList
                parsed.include shouldBe emptyList()
            }

            context("subscription with both all and include (synthetic sources)") {
                val includeList = listOf("syntheticSource")
                val message = ReceiverAudioSubscriptionMessage(all = true, include = includeList)
                val parsed = parse(message.toJson())

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe true
                parsed.include shouldContainExactly includeList
                parsed.exclude shouldBe emptyList()
            }

            context("subscription with empty lists") {
                val message = ReceiverAudioSubscriptionMessage(all = false)
                val parsed = parse(message.toJson())

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.include shouldBe emptyList()
                parsed.exclude shouldBe emptyList()
            }

            context("parsing from JSON with include and exclude") {
                val jsonString = """
                    {
                        "colibriClass": "ReceiverAudioSubscription",
                        "all": true,
                        "include": ["endpoint1", "endpoint2"],
                        "exclude": ["endpoint3"]
                    }
                """
                val parsed = parse(jsonString)

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe true
                parsed.include shouldContainExactly listOf("endpoint1", "endpoint2")
                parsed.exclude shouldContainExactly listOf("endpoint3")
            }

            context("parsing from JSON with missing fields uses defaults") {
                val jsonString = """
                    {
                        "colibriClass": "ReceiverAudioSubscription"
                    }
                """
                val parsed = parse(jsonString)

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe false
                parsed.include shouldBe emptyList()
                parsed.exclude shouldBe emptyList()
            }

            context("parsing legacy mode=All syntax") {
                val jsonString = """
                    {
                        "colibriClass": "ReceiverAudioSubscription",
                        "mode": "All"
                    }
                """
                val parsed = parse(jsonString)

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe true
                parsed.include shouldBe emptyList()
                parsed.exclude shouldBe emptyList()
            }

            context("parsing legacy mode=None syntax") {
                val jsonString = """
                    {
                        "colibriClass": "ReceiverAudioSubscription",
                        "mode": "None"
                    }
                """
                val parsed = parse(jsonString)

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe false
                parsed.include shouldBe emptyList()
                parsed.exclude shouldBe emptyList()
            }

            context("parsing legacy mode=Include syntax") {
                val jsonString = """
                    {
                        "colibriClass": "ReceiverAudioSubscription",
                        "mode": "Include",
                        "list": ["source1", "source2"]
                    }
                """
                val parsed = parse(jsonString)

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe false
                parsed.include shouldContainExactly listOf("source1", "source2")
                parsed.exclude shouldBe emptyList()
            }

            context("parsing legacy mode=Exclude syntax") {
                val jsonString = """
                    {
                        "colibriClass": "ReceiverAudioSubscription",
                        "mode": "Exclude",
                        "list": ["source3"]
                    }
                """
                val parsed = parse(jsonString)

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe true
                parsed.include shouldBe emptyList()
                parsed.exclude shouldContainExactly listOf("source3")
            }

            context("parsing legacy mode=Include syntax with no list") {
                val jsonString = """
                    {
                        "colibriClass": "ReceiverAudioSubscription",
                        "mode": "Include"
                    }
                """
                val parsed = parse(jsonString)

                parsed.shouldBeInstanceOf<ReceiverAudioSubscriptionMessage>()
                parsed.all shouldBe false
                parsed.include shouldBe emptyList()
                parsed.exclude shouldBe emptyList()
            }
        }

        xcontext("Serializing performance") {
            val times = 1_000_000

            val objectMapper = ObjectMapper()
            fun toJsonJackson(m: DominantSpeakerMessage): String = objectMapper.writeValueAsString(m)
            fun toJsonJsonSimple(m: DominantSpeakerMessage) = jacksonObjectMapper().createObjectNode().apply {
                put("dominantSpeakerEndpoint", m.dominantSpeakerEndpoint)
            }.toString()

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
                    f(DominantSpeakerMessage(i.toString()))
                }
                val end = System.currentTimeMillis()

                return end - start
            }

            System.err.println("Times=$times")
            System.err.println("Jackson: ${runTest { toJsonJackson(it) }}")
            System.err.println("Json-simple: ${runTest { toJsonJsonSimple(it) }}")
            System.err.println("String concat: ${runTest { toJsonStringConcat(it) }}")
            System.err.println("String template: ${runTest { toJsonStringTemplate(it) }}")
            System.err.println("Raw string template: ${runTest { toJsonRawStringTemplate(it) }}")
            System.err.println("Raw string template (trim): ${runTest { toJsonRawStringTemplate(it).trimMargin() }}")
        }
    }

    companion object {
        const val ENDPOINT_MESSAGE = """
            {
              "colibriClass": "EndpointMessage",
              "to": "to_value",
              "other_field1": "other_value1",
              "other_field2": 97,
              "other_null": null
            }
        """

        const val VIDEO_CONSTRAINTS = """
            {
                "maxHeight": 1080,
                "maxFrameRate": 15.0
            }
        """

        const val RECEIVER_VIDEO_CONSTRAINTS_EMPTY = """
            {
              "colibriClass": "ReceiverVideoConstraints"
            }
        """
        const val RECEIVER_VIDEO_CONSTRAINTS = """
            {
              "colibriClass": "ReceiverVideoConstraints",
              "lastN": 3,
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
