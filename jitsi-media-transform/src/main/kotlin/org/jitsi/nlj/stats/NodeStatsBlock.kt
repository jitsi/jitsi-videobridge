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

package org.jitsi.nlj.stats

import org.jitsi.nlj.util.appendLnIndent

class NodeStatsBlock(val name: String) {
    val stats = mutableMapOf<String, Any?>()

    fun addStat(name: String, value: Any) {
        stats[name] = value
    }

    fun addStat(data: String) {
        stats[data] = null
    }

    /**
     * Add all the stats from [otherBlock], but don't use its name
     */
    fun addAll(otherBlock: NodeStatsBlock) {
        stats.putAll(otherBlock.stats)
    }

    fun prettyPrint(indentLevel: Int = 0): String {
        return with(StringBuffer()) {
            appendLnIndent(indentLevel, name)
            stats.forEach { statName, statValue ->
                when (statValue) {
                    is NodeStatsBlock -> {
                        appendln(statValue.prettyPrint(indentLevel + 2))
                    }
                    is Any -> {
                        // statValue is Any, so we know it's non-null
                        appendLnIndent(indentLevel + 2, "$statName: $statValue")
                    }
                    else -> {
                        // statValue is null, just print statName
                        appendLnIndent(indentLevel + 2, statName)
                    }
                }
            }
            toString()
        }
    }
}