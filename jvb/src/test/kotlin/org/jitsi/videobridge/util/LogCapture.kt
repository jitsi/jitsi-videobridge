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
package org.jitsi.videobridge.util

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Collects the records of level INFO and above that the `java.util.logging` logger with the given name receives, as
 * text: the message plus the message of the exception, if any. Call [close] to detach it.
 */
class LogCapture(private val loggerName: String) : AutoCloseable {
    private val jul = Logger.getLogger(loggerName)
    private val lines = mutableListOf<String>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            // Only what is logged at the default level, so that the result does not depend on the log level.
            if (record.level.intValue() < Level.INFO.intValue()) return
            synchronized(lines) {
                lines.add(record.message + (record.thrown?.let { " | ${it.javaClass.name}: ${it.message}" } ?: ""))
            }
        }
        override fun flush() {}
        override fun close() {}
    }.also { jul.addHandler(it) }

    val text: String get() = synchronized(lines) { lines.joinToString("\n") }

    override fun close() = jul.removeHandler(handler)
}
