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

package org.jitsi.videobridge.config

import com.typesafe.config.Config
import org.jitsi.metaconfig.ConfigSource
import java.time.Duration
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.typeOf

class NewTypesafeConfigSource(override val name: String, private val config: Config) : ConfigSource {
//    init {
//        println(config.root().render())
//    }
    @ExperimentalStdlibApi
    override fun getterFor(type: KType): (String) -> Any {
        return when (type) {
            typeOf<Boolean>() -> { key -> config.getBoolean(key) }
            typeOf<Int>() -> { key -> config.getInt(key) }
            typeOf<Long>() -> { key -> config.getLong(key) }
            typeOf<String>() -> { key -> config.getString(key) }
            typeOf<List<String>>() -> { key -> config.getStringList(key) }
            typeOf<List<Int>>() -> { key -> config.getIntList(key) }
            typeOf<Duration>() -> { key -> config.getDuration(key) }

            in customParsers -> {
                { key ->
                    val obj = config.getConfig(key)
                    val parser = customParsers[type]!!
                    parser(obj)
                }
            }
            else -> {
                if (type.isSubtypeOf(typeOf<List<*>>())) {
                    println("TEMP is a List, maybe we have a custom parser for inner type?")
                    // Support retrieving a List<MyType> when a custom parser for MyType
                    // has been added
                    val innerType = type.arguments.first().type!!
                    if (innerType in customParsers) {
                        println("TEMP: have a custom parser for type $innerType")
                        return { key ->
                            val list = config.getObjectList(key)
                            list.map {
                                customParsers[innerType]?.invoke(it.toConfig())
                            }
                        }
                    }
                    println("TEMP: no custom parser for type $innerType: ${customParsers.keys}")
                }
                TODO("no support for type $type")
            }
        }
    }

    private val customParsers = mutableMapOf<KType, (Config) -> Any>()

    fun addCustomParser(type: KType, parser: (Config) -> Any) {
        customParsers[type] = parser
    }
}
