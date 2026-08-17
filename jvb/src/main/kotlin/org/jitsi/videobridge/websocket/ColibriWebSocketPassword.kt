/*
 * Copyright @ 2026 - Present, 8x8 Inc
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
package org.jitsi.videobridge.websocket

import java.security.SecureRandom

private val random = SecureRandom()
private const val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

/**
 * Generate a password which authenticates a colibri WebSocket. The password is used in a URL query parameter, so
 * it only contains URL-safe characters.
 *
 * The peer receives the URL once, when we describe its transport, and re-dials that same URL each time the
 * WebSocket reconnects. Because of this, the password must stay the same for the lifetime of the endpoint or
 * relay which owns it.
 */
fun generateColibriWebSocketPassword(length: Int = 24) =
    String(CharArray(length) { CHARS[random.nextInt(CHARS.length)] })
