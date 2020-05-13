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

@file:Suppress("unused")

package org.jitsi.videobridge.api.client.v1

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.HttpTimeout
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.ColibriIQProvider
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.provider.ProviderManager

/**
 * JVB Client API for controlling a JVB instance
 */
class JvbApi(private val jvbUrl: String) {
    private val client = HttpClient(Apache) {
        install(XmlFeature)
        install(HttpTimeout) {
            this.requestTimeoutMillis = 5000
        }
    }

    /**
     * Send a [ColibriConferenceIQ] message to the JVB for processing
     * and receive an [IQ] back.  Call is synchronous.
     */
    fun sendColibri(colibri: ColibriConferenceIQ): IQ {
        return runBlocking {
            client.post<IQ>("$jvbUrl/v1/colibri") {
                contentType(ContentType.Application.Xml)
                body = colibri
            }
        }
    }
}

fun main() {
    ProviderManager.addIQProvider(
        ColibriConferenceIQ.ELEMENT_NAME,
        ColibriConferenceIQ.NAMESPACE,
        ColibriIQProvider())
    val x = ColibriConferenceIQ()
    val api = JvbApi("http://127.0.0.1:9090")
    val resp = api.sendColibri(x)
    println("got resp: ${resp.toXML()}")
}
