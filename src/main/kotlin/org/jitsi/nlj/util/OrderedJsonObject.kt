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

package org.jitsi.nlj.util

import org.json.simple.JSONObject

/**
 * Functions just like [JSONObject], but preserves the order
 * in which keys were added when serializing to a JSON string
 * (which is useful for things like stats where we want to group
 * similar values and preserve the pipeline stats order).
 */
class OrderedJsonObject : JSONObject() {
    private val data = LinkedHashMap<Any, Any>()

    override fun put(key: Any?, value: Any?): Any? = data.put(key!!, value!!)

    override fun toJSONString(): String = toJSONString(data)
}