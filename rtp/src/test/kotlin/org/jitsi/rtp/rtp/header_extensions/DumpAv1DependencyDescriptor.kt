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
package org.jitsi.rtp.rtp.header_extensions

import jakarta.xml.bind.DatatypeConverter.parseHexBinary

fun main(args: Array<String>) {
    var structure: Av1TemplateDependencyStructure? = null
    var line: String?
    while (readLine().also { line = it } != null) {
        try {
            val descBinary = parseHexBinary(line)
            val reader = Av1DependencyDescriptorReader(descBinary, 0, descBinary.size)
            val desc = reader.parse(structure)
            desc.newTemplateDependencyStructure?.let { structure = it }
            println(desc.toJSONString())
            val frameInfo = desc.frameInfo
            println(frameInfo)
        } catch (e: Exception) {
            println(e.message)
        }
    }
}
