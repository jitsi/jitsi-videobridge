/*
 * Copyright @ 2026 - present 8x8, Inc.
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

@file:JvmName("LogUtils")

package org.jitsi.videobridge.util

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException

private const val MAX_DESCRIPTION_LENGTH = 200

/**
 * A short description of this exception that is safe to log when the exception was caused by a message that can hold
 * the content of a meeting (chat, transcripts, audio). It never quotes the message of the exception, because the
 * message of a JSON error can quote the input. It is the class of the exception, and for a JSON error also the path
 * of the field and the location of the error.
 */
fun Throwable.messageWithoutInput(): String {
    val description = StringBuilder(javaClass.simpleName)
    if (this is JsonMappingException && path.isNotEmpty()) {
        description.append(" at ").append(path.joinToString(".") { it.fieldName ?: "[${it.index}]" })
    }
    if (this is JsonProcessingException) {
        location?.let { description.append(" (line ${it.lineNr}, column ${it.columnNr})") }
    }
    return description.toString().take(MAX_DESCRIPTION_LENGTH)
}
