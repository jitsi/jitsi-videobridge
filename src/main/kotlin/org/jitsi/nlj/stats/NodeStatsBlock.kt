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
import org.json.simple.JSONObject

class NodeStatsBlock(val name: String) {
    val stats = mutableMapOf<String, Any>()

    /**
     * Adds a stat with a number value. Integral values are promoted to [Long], while floating point values are
     * promoted to [Double].
     */
    fun addNumber(name: String, value: Number) {
        promote(value)?.let { doAddStat(name, it) }
    }

    /**
     * Adds a stat with a string value.
     */
    fun addString(name: String, value: String) {
        doAddStat(name, value)
    }

    /**
     * Adds another [NodeStatsBlock] as a child.
     */
    fun addBlock(otherBlock: NodeStatsBlock) {
        doAddStat(otherBlock.name, otherBlock)
    }

    private fun doAddStat(name: String, value: Any) {
        stats[name] = value
    }

    /**
     * Aggregates another block into this one. That is, takes any stats with number values from the
     * other block and updates the current block with the sum of the current and other value.
     */
    fun aggregate(otherBlock: NodeStatsBlock) {
        otherBlock.stats.forEach { name, value ->
            val existingValue = stats[name]
            // We only aggregate numbers, and we "only" handle Long and Double because we've already
            // promoted them when adding.
            when {
                existingValue == null && (value is Long || value is Double)
                    -> stats[name] = value
                existingValue is Long && value is Long
                    -> stats[name] = existingValue + value
                existingValue is Double && value is Double
                    -> stats[name] = existingValue + value
                existingValue is Long && value is Double
                    -> stats[name] = existingValue + value
                existingValue is Double && value is Long
                    -> stats[name] = existingValue + value
            }
        }
        stats[AGGREGATES] = (stats.getOrDefault(AGGREGATES, 0L) as Long) + 1
    }

    /**
     * Promotes integer values to [Long] and floating point values to [Double]. Returns a
     * [Long], [Double], or null.
     */
    private fun promote(n: Any): Number? = when (n) {
        is Byte -> n.toLong()
        is Short -> n.toLong()
        is Int -> n.toLong()
        is Long -> n
        is Float -> n.toDouble()
        is Double -> n
        else -> null
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

    /**
     * Returns a JSON representation of this [NodeStatsBlock].
     */
    fun toJson(): JSONObject = JSONObject().apply {
        stats.forEach { name, value ->
            when (value) {
                is NodeStatsBlock -> put(name, value.toJson())
                else -> put(name, value)
            }
        }
    }

    companion object {
        /**
         * The stat name that we use to could the number of other block aggregated in this one.
         */
        private val AGGREGATES = "_aggregates"
    }
}
