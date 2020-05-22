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

package org.jitsi.videobridge.signaling.api

import org.jitsi.xmpp.extensions.AbstractPacketExtension
import org.jivesoftware.smack.util.XmlStringBuilder

class SignalingApiExtension(
    private val url: String,
    private val version: String
) : AbstractPacketExtension("https://jitsi.org/protocol/colibri", "api") {

    override fun toXML(): String = with(XmlStringBuilder()) {
        halfOpenElement(elementName)
        attribute(URL_ATTR_NAME, url)
        attribute(VERSION_ATTR_NAME, version)
        closeEmptyElement()
        toString()
    }

    companion object {
        const val NAMESPACE = "http://jitsi.org/protocol/colibri"
        const val ELEMENT_NAME = "api"
        const val URL_ATTR_NAME = "url"
        const val VERSION_ATTR_NAME = "version"
    }
}
