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
import org.jitsi.videobridge.colibri2.createConferenceAlreadyExistsError
import org.jitsi.videobridge.colibri2.createConferenceNotFoundError
import org.jitsi.videobridge.colibri2.toRedactedXml
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Connect
import org.jitsi.xmpp.extensions.colibri2.IqProviderUtils
import org.jivesoftware.smack.util.PacketParserUtils
import java.net.URI

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
        should("redact the header values of a connect, keeping the rest of the request") {
            val connect = Connect(
                id = "agent-1",
                url = URI("wss://relay.example.com/agent"),
                protocol = Connect.Protocols.MEDIAJSON,
                type = Connect.Types.AGENT,
                audio = true,
                create = true
            ).apply { addHttpHeader("X-Agent-Token", "s3cret-token") }
            val request = ConferenceModifyIQ.builder("id").setMeetingId("m").addConnect(connect).build()

            val xml = request.toRedactedXml()
            xml shouldNotContain "s3cret-token"
            xml shouldContain "name=\"X-Agent-Token\""
            xml shouldContain "value=\"[redacted]\""
            xml shouldContain "wss://relay.example.com/agent"
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
