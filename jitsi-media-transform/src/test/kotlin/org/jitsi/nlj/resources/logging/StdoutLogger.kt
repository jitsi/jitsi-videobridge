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

package org.jitsi.nlj.resources.logging

import org.jitsi.utils.logging2.LogContext
import org.jitsi.utils.logging2.Logger
import java.util.function.Supplier
import java.util.logging.Level

/**
 * Logger which logs all messages to stdout (but obeys a set log level).  By default
 * the log level is [Level.OFF], so nothing is logged. Child loggers inherit the current
 * log level from the parent, but future changes to the parent logger's level don't affect
 * children.
 */
class StdoutLogger(
    private val name: String = "root",
    private var _level: Level = Level.OFF,
    context: Map<String, String> = emptyMap()
) : Logger {
    private val context = LogContext(context)
    override fun createChildLogger(name: String): Logger = StdoutLogger(name, level)
    override fun createChildLogger(name: String, context: MutableMap<String, String>): Logger =
        StdoutLogger(name, _level, context)

    private fun isLoggable(level: Level): Boolean = level.intValue() >= _level.intValue()

    private fun log(level: Level, msg: Any) {
        if (isLoggable(level)) {
            println("$name: $context $msg")
        }
    }
    private fun log(level: Level, msgSupplier: () -> String) {
        if (isLoggable(level)) {
            println("$name: $context ${msgSupplier()}")
        }
    }
    private fun log(level: Level, msg: Any, throwable: Throwable) {
        log(level, "$msg $throwable")
    }

    override fun setLevelOff() {
        setLevel(Level.OFF)
    }

    override fun setLevelError() {
        _level = Level.SEVERE
    }
    override fun error(msg: Any) {
        log(Level.SEVERE, msg)
    }

    override fun error(msgSupplier: Supplier<String>) {
        log(Level.SEVERE, msgSupplier)
    }
    override fun error(msg: Any, throwable: Throwable) {
        log(Level.SEVERE, msg, throwable)
    }

    override fun isWarnEnabled(): Boolean = isLoggable(Level.WARNING)
    override fun setLevelWarn() {
        setLevel(Level.WARNING)
    }
    override fun warn(msg: Any) {
        log(Level.WARNING, msg)
    }

    override fun warn(msgSupplier: Supplier<String>) {
        log(Level.WARNING, msgSupplier)
    }

    override fun warn(msg: Any, throwable: Throwable) {
        log(Level.WARNING, msg, throwable)
    }

    override fun isInfoEnabled(): Boolean = isLoggable(Level.INFO)
    override fun setLevelInfo() {
        setLevel(Level.INFO)
    }
    override fun info(msg: Any) {
        log(Level.INFO, msg)
    }
    override fun info(msgSupplier: Supplier<String>) {
        log(Level.INFO, msgSupplier)
    }

    override fun isDebugEnabled(): Boolean = isLoggable(Level.FINE)
    override fun setLevelDebug() {
        setLevel(Level.FINE)
    }
    override fun debug(msg: Any) {
        log(Level.FINE, msg)
    }
    override fun debug(msgSupplier: Supplier<String>) {
        log(Level.FINE, msgSupplier)
    }

    override fun isTraceEnabled(): Boolean = isLoggable(Level.FINER)
    override fun setLevelTrace() {
        setLevel(Level.FINER)
    }
    override fun trace(msg: Any) {
        log(Level.FINER, msg)
    }
    override fun trace(msgSupplier: Supplier<String>) {
        log(Level.FINER, msgSupplier)
    }

    override fun setLevelAll() {
        setLevel(Level.ALL)
    }
    override fun getLevel(): Level = _level
    override fun setLevel(level: Level) {
        _level = level
    }
}