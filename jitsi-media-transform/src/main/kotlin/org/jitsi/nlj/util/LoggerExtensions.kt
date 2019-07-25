/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.util

import org.jitsi.utils.logging.Logger
import kotlin.reflect.KClass

// Logger already has functions 'info', 'debug', etc. which take in Object, so we can't
// override those.  The 'c' in these methods stands for 'conditional' as the given
// function is only executed if that log level is enabled.

inline fun Logger.cinfo(msg: () -> String) {
    if (isInfoEnabled) {
        this.info(msg())
    }
}

inline fun Logger.cdebug(msg: () -> String) {
    if (isDebugEnabled) {
        this.debug(msg())
    }
}

inline fun Logger.cwarn(msg: () -> String) {
    if (isWarnEnabled) {
        this.warn(msg())
    }
}

inline fun Logger.cerror(msg: () -> String) {
    this.error(msg())
}
// Logger helpers below taken from https://stackoverflow.com/a/34462577

// Return logger for Java class, if companion object fix the name
fun <T : Any> getLogger(forClass: Class<T>): Logger {
    return Logger.getLogger(forClass.name)
}

/**
 * Create a new logger which delegates its level to another logger
 */
fun <T : Any> getLogger(forClass: Class<T>, levelDelegate: Logger?): Logger {
    return if (levelDelegate != null) {
        getLogger(getLogger(forClass), levelDelegate)
    } else {
        getLogger(forClass)
    }
}

/**
 * Create a logger using the given loggerDelegate and levelDelegate
 */
fun getLogger(loggerDelegate: Logger, levelDelegate: Logger?): Logger {
    return if (levelDelegate != null) {
        Logger.getLogger(loggerDelegate, levelDelegate)
    } else {
        // TODO(brian): not sure if this makes sense: if no level delegate was passed, just use the logger
        // delegate as the level delegate?  There's no helper to use a logger delegate but not a level delegate
        Logger.getLogger(loggerDelegate, loggerDelegate)
    }
}

// Return logger for Kotlin class
fun <T : Any> logger(forClass: KClass<T>): Logger {
    return getLogger(forClass.java)
}

fun StringBuffer.appendIndent(numSpaces: Int, msg: String) {
    append(" ".repeat(numSpaces)).append(msg)
}

fun StringBuffer.appendLnIndent(numSpaces: Int, msg: String) {
    append(" ".repeat(numSpaces)).appendln(msg)
}