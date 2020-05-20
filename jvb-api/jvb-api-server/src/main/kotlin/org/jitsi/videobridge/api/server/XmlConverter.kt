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

package org.jitsi.videobridge.api.server

import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.content.TextContent
import io.ktor.features.ContentConverter
import io.ktor.http.ContentType
import io.ktor.request.ApplicationReceiveRequest
import io.ktor.request.contentCharset
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.decode
import io.ktor.utils.io.readRemaining
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.util.PacketParserUtils

/**
 * A server-side content serialization/deserialization feature for
 * handling XML requests and responses.
 *
 * This class handles all requests with a content type of 'application/xml' and
 * assumes that the body being sent can be parsed into an
 * [org.jivesoftware.smack.packet.IQ].  The IQ parsing is done via
 * [PacketParserUtils.parseIQ], so it requires that any necessary
 * [org.jivesoftware.smack.provider.IQProvider]s have been installed.
 *
 * It assumes responses will be instances of [Stanza].
 */
object XmlConverter : ContentConverter {
    override suspend fun convertForReceive(context: PipelineContext<ApplicationReceiveRequest, ApplicationCall>): Any? {
        val request = context.subject
        val channel = request.value as? ByteReadChannel ?: return null
        val reader = (context.call.request.contentCharset() ?: Charsets.UTF_8).newDecoder().decode(channel.readRemaining()).reader()
        val parser = PacketParserUtils.getParserFor(reader)
//        val parser = PacketParserUtils.newXmppParser(reader)
//        // Prime the parser
//        parser.next()
        return PacketParserUtils.parseIQ(parser)
    }

    override suspend fun convertForSend(context: PipelineContext<Any, ApplicationCall>, contentType: ContentType, value: Any): Any? {
        return TextContent((value as Stanza).toXML().toString(), contentType)
    }
}
