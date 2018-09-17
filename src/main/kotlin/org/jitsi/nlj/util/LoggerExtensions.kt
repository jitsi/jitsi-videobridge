/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.util.Logger
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObject

// Logger already has functions 'info', 'debug', etc. which take in Object, so we can't
// override those.  The 'c' in these methods stands for 'conditional' as the given
// function is only executed if that log level is enabled.

fun Logger.cinfo(msg: () -> String) {
    if (isInfoEnabled) {
        this.info(msg())
    }
}

fun Logger.cdebug(msg: () -> String) {
    if (isDebugEnabled) {
        this.debug(msg())
    }
}

fun Logger.cwarn(msg: () -> String) {
    if (isWarnEnabled) {
        this.warn(msg())
    }
}

fun Logger.cerror(msg: () -> String) {
    this.error(msg())
}
// Logger helpers below taken from https://stackoverflow.com/a/34462577

// Return logger for Java class, if companion object fix the name
fun <T: Any> getLogger(forClass: Class<T>): Logger {
    return Logger.getLogger(forClass.name)
}

// Return logger for Kotlin class
fun <T: Any> logger(forClass: KClass<T>): Logger {
    return getLogger(forClass.java)
}
