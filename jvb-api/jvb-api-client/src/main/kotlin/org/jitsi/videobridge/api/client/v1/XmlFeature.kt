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

package org.jitsi.videobridge.api.client.v1

import io.ktor.client.HttpClient
import io.ktor.client.features.HttpClientFeature
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.accept
import io.ktor.client.statement.HttpResponseContainer
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.ContentType
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.decode
import io.ktor.utils.io.readRemaining
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.util.PacketParserUtils

/**
 * A client-side content serialization/deserialization feature for
 * handling XML requests and responses.
 *
 * This class handles all requests with a content type of 'application/xml' and
 * assumes that the body being sent is an instance of [Stanza].
 *
 * It assumes responses will be instances of [org.jivesoftware.smack.packet.IQ].
 */
class XmlFeature {
    class Config

    companion object Feature : HttpClientFeature<Config, XmlFeature> {
        override val key: AttributeKey<XmlFeature> = AttributeKey("Xml")

        override fun prepare(block: Config.() -> Unit): XmlFeature {
            return XmlFeature()
        }

        override fun install(feature: XmlFeature, scope: HttpClient) {
            // Intercept outgoing requests and serialize the body to an XML
            // string
            scope.requestPipeline.intercept(HttpRequestPipeline.Transform) { payload ->
                context.accept(ContentType.Application.Xml)

                if (context.contentType()?.match(ContentType.Application.Xml) != true) {
                    return@intercept
                }
                val serializedContent = when (payload) {
                    is Stanza -> payload.toXML().toString()
                    else -> throw IllegalArgumentException("Unsupported XML type: ${payload::class}")
                }
                proceedWith(serializedContent)
            }

            // Intercept incoming requests and deserialize the body into an IQ
            scope.responsePipeline.intercept(HttpResponsePipeline.Transform) { (info, body) ->
                if (body !is ByteReadChannel) return@intercept

                // Only handle XML
                if (context.response.contentType()?.match(ContentType.Application.Xml) != true) {
                    return@intercept
                }
                val reader = (context.response.charset() ?: Charsets.UTF_8).newDecoder().decode(body.readRemaining()).reader()
                val parser = PacketParserUtils.newXmppParser(reader)
                parser.next()
                val parsedBody = PacketParserUtils.parseIQ(parser)
                proceedWith(HttpResponseContainer(info, parsedBody))
            }
        }
    }
}
