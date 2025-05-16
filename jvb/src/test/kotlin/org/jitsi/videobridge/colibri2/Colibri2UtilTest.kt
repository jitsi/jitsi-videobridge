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
import org.jitsi.videobridge.colibri2.createConferenceAlreadyExistsError
import org.jitsi.videobridge.colibri2.createConferenceNotFoundError
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.IqProviderUtils
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
