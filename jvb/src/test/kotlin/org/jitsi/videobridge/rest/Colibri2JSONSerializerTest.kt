/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.videobridge.rest

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.IqProviderUtils
import org.jivesoftware.smack.util.PacketParserUtils
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.xmlunit.builder.DiffBuilder

class Colibri2JSONSerializerTest : ShouldSpec() {

    init {
        IqProviderUtils.registerProviders()

        context("serializing an IQ") {
            val parser = PacketParserUtils.getParserFor(expectedXml)
            val iq = PacketParserUtils.parseIQ(parser)

            iq.shouldBeInstanceOf<ConferenceModifyIQ>()

            val json = Colibri2JSONSerializer.serializeConferenceModify(iq).toJSONString()

            should("create the correct JSON") {
                json.shouldEqualJson(expectedJson)
            }
        }

        context("deserializing JSON") {
            val parser = JSONParser()
            val json = parser.parse(expectedJson)
            json.shouldBeInstanceOf<JSONObject>()
            val iq = Colibri2JSONDeserializer.deserializeConferenceModify(json)

            val xml = iq.toXML().toString()

            should("create the correct XML") {
                val diff = DiffBuilder.compare(xml).withTest(expectedXml)
                    .ignoreWhitespace()
                    .checkForIdentical().build()

                withClue(diff.toString()) {
                    diff.hasDifferences() shouldBe false
                }
            }
        }
    }

    companion object {
        private val expectedXml =
            /* Same XML as Colibri2IQTest in jitsi-xmpp-extensions */
            """
            <iq xmlns='jabber:client' id='id' type='get'>
              <conference-modify xmlns='jitsi:colibri2' meeting-id='88ff288c-5eeb-4ea9-bc2f-93ea38c43b78' name='myconference@jitsi.example' callstats-enabled='false' create='true'>
                <endpoint xmlns='jitsi:colibri2' id='bd9b6765' stats-id='Jayme-Clv'>
                  <media type='audio'>
                    <payload-type xmlns='urn:xmpp:jingle:apps:rtp:1' name='opus' clockrate='48000' channels='2'/>
                  </media>
                  <transport ice-controlling='true'/>
                  <sources>
                    <media-source type='video' id='bd9b6765-v1'>
                      <source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0' ssrc='803354056'/>
                    </media-source>
                  </sources>
                  <force-mute audio='true' video='true'/>
                </endpoint>
              </conference-modify>
            </iq>
            """.trimIndent()

        private val expectedJson =
            """
            {
              "meeting-id":"88ff288c-5eeb-4ea9-bc2f-93ea38c43b78",
              "name":"myconference@jitsi.example",
              "callstats-enabled":false,
              "create":true,
              "endpoints":[
                {
                   "id": "bd9b6765",
                   "stats-id": "Jayme-Clv",
                   "medias": [{"type":"audio", "payload-types": [{"name":"opus", "clockrate":"48000", "channels": "2"}]}],
                   "transport": {"ice-controlling":true},
                   "sources": [{"type":"video", "id":"bd9b6765-v1", "sources":[803354056]}],
                   "force-mute": {"audio":true, "video":true}
                }
              ]
            }
            """.trimIndent()
    }
}
