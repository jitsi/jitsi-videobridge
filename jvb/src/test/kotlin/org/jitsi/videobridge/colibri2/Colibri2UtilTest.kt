/*
 * Copyright @ 2022 - Present, 8x8 Inc
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
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.jitsi.config.withNewConfig
import org.jitsi.videobridge.colibri2.createConferenceAlreadyExistsError
import org.jitsi.videobridge.colibri2.createConferenceNotFoundError
import org.jitsi.videobridge.colibri2.createFeatureNotImplementedError
import org.jitsi.videobridge.colibri2.toRedactedXml
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.IqProviderUtils
import org.jitsi.xmpp.util.RedactColibri
import org.jivesoftware.smack.util.PacketParserUtils

class Colibri2UtilTest : ShouldSpec({
    IqProviderUtils.registerProviders()
    val iq = ConferenceModifyIQ.builder("id").setMeetingId("m").build()
    context("createConferenceAlreadyExistsError") {
        val error = createConferenceAlreadyExistsError(iq, "i")

        val parsedIq = parseIQ(error.toXML().toString())
        val colibri2ErrorExtension =
            parsedIq.error.getExtension<Colibri2Error>(Colibri2Error.ELEMENT, Colibri2Error.NAMESPACE)
        colibri2ErrorExtension shouldNotBe null
        colibri2ErrorExtension.reason shouldBe Colibri2Error.Reason.CONFERENCE_ALREADY_EXISTS
    }

    context("toRedactedXml") {
        // A request with a connect carrying a secret header and an endpoint with a non-relay candidate.
        val request = parseIQ(
            """
            <iq xmlns="jabber:client" type="set" id="id">
              <conference-modify xmlns="jitsi:colibri2" meeting-id="m">
                <endpoint id="ep">
                  <transport>
                    <transport xmlns="urn:xmpp:jingle:transports:ice-udp:1" ufrag="u" pwd="p">
                      <candidate component="1" foundation="1" generation="0" id="c1" network="0" priority="1"
                                 protocol="udp" type="host" ip="203.0.113.7" port="10000"/>
                    </transport>
                  </transport>
                </endpoint>
                <connects>
                  <connect id="agent-1" url="wss://relay.example.com/agent" protocol="mediajson" type="agent"
                           audio="true">
                    <http-header name="X-Agent-Token" value="s3cret-token"/>
                  </connect>
                </connects>
              </conference-modify>
            </iq>
            """.trimIndent()
        ) as ConferenceModifyIQ

        should("redact the header values and remote addresses by default, keeping the rest of the request") {
            val xml = request.toRedactedXml()
            xml shouldNotContain "s3cret-token"
            xml shouldContain "name=\"X-Agent-Token\""
            xml shouldContain "value=\"[redacted]\""
            xml shouldContain "wss://relay.example.com/agent"
            xml shouldNotContain "203.0.113.7"
            xml shouldContain "ip=\"xx.xx.xx.xx\""
        }
        should("not redact the header values when configured not to") {
            withNewConfig("videobridge.redact-colibri-http-headers=false") {
                val xml = request.toRedactedXml()
                xml shouldContain "value=\"s3cret-token\""
                xml shouldNotContain "203.0.113.7"
            }
        }
        should("not redact the remote addresses when configured not to") {
            withNewConfig("videobridge.redact-remote-addresses=false") {
                val xml = request.toRedactedXml()
                xml shouldContain "ip=\"203.0.113.7\""
                xml shouldNotContain "s3cret-token"
            }
        }
        should("stand in a placeholder, never the raw XML, when redaction fails") {
            mockkObject(RedactColibri.Companion)
            try {
                every { RedactColibri.redactHttpHeaderValues(any()) } throws RuntimeException("no transformer")

                val xml = request.toRedactedXml()
                xml shouldNotContain "s3cret-token"
                xml shouldNotContain "203.0.113.7"
                xml shouldContain "conference-modify id for meeting m could not be redacted"
                xml shouldContain "no transformer"
            } finally {
                unmockkObject(RedactColibri.Companion)
            }
        }
        // Conference logs its responses unredacted, which relies on the next two: neither kind of response carries
        // the request's connects or candidates.
        should("not be needed for an error response, which echoes only the empty conference-modify element") {
            val xml = createFeatureNotImplementedError(request, "not supported").toXML().toString()
            xml shouldContain "feature-not-implemented"
            xml shouldContain "<conference-modify"
            xml shouldNotContain "s3cret-token"
            xml shouldNotContain "203.0.113.7"
        }
        should("not be needed for a success response, which takes only the request's addressing") {
            val xml = ConferenceModifiedIQ.builder(ConferenceModifiedIQ.Builder.createResponse(request)).build()
                .toXML().toString()
            xml shouldContain "<conference-modified"
            xml shouldContain "id='id'"
            xml shouldNotContain "s3cret-token"
            xml shouldNotContain "203.0.113.7"
        }
    }

    context("createConferenceNotFoundError") {
        val error = createConferenceNotFoundError(iq, "i")

        val parsedIq = parseIQ(error.toXML().toString())
        val colibri2ErrorExtension =
            parsedIq.error.getExtension<Colibri2Error>(Colibri2Error.ELEMENT, Colibri2Error.NAMESPACE)
        colibri2ErrorExtension shouldNotBe null
        colibri2ErrorExtension.reason shouldBe Colibri2Error.Reason.CONFERENCE_NOT_FOUND
    }
})

fun parseIQ(xml: String) = PacketParserUtils.parseIQ(PacketParserUtils.getParserFor(xml))
