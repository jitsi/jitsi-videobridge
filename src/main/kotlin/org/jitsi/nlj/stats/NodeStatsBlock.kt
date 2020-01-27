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

import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.nlj.util.appendLnIndent
import org.json.simple.JSONObject

class NodeStatsBlock(val name: String) {
    private val stats = mutableMapOf<String, Any>()
    /**
     * Holds stats that are computed based on other values in the map (to e.g. calculate the
     * ratio of two values). Restricted to [Number] because this makes it easier to implement and
     * we don't need other values right now.
     */
    private val compoundStats = mutableMapOf<String, (NodeStatsBlock) -> Number>()

    /**
     * Adds a stat with a number value. Integral values are promoted to [Long], while floating point values are
     * promoted to [Double].
     */
    fun addNumber(name: String, value: Number) {
        promote(value)?.let { stats[name] = it }
    }

    /**
     * Adds a stat with a string value.
     */
    fun addString(name: String, value: String) {
        stats[name] = value
    }

    /**
     * Adds a stat with a boolean value.
     */
    fun addBoolean(name: String, value: Boolean) {
        stats[name] = value
    }

    /**
     * Adds another [NodeStatsBlock] as a child.
     */
    fun addBlock(otherBlock: NodeStatsBlock) {
        stats[otherBlock.name] = otherBlock
    }

    /**
     * Adds a block with a given name from a JSON object.
     */
    fun addJson(name: String, json: JSONObject) {
        addBlock(fromJson(name, json))
    }

    /**
     * Adds a block with a given name from an ordered JSON object.
     */
    fun addJson(name: String, json: OrderedJsonObject) {
        addBlock(fromJson(name, json))
    }

    /**
     * Adds a named value to this [NodeStatsBlock] which is derived from other values in the block.
     * The value will be calculated (by invoking the given function) when it is needed (e.g. in [getValue] or
     * when exporting this block to another format (printing or JSON).
     */
    fun addCompoundValue(name: String, compoundValue: (NodeStatsBlock) -> Number) {
        compoundStats[name] = compoundValue
    }

    fun getValue(name: String): Any? = when {
        stats.containsKey(name) -> stats[name]
        compoundStats.containsKey(name) -> compoundStats[name]?.invoke(this)
        else -> null
    }

    /**
     * Gets the value of a stat with a given name, if this [NodeStatsBlock] has it and it is a [Number].
     * Otherwise returns 'null'.
     */
    fun getNumber(name: String): Number? = when {
        stats[name] is Number -> stats[name] as Number
        compoundStats.containsKey(name) -> compoundStats[name]?.invoke(this)
        else -> null
    }

    fun getNumberOrDefault(name: String, default: Number): Number = getNumber(name) ?: default

    /**
     * Aggregates another block into this one. That is, takes any stats with number values from the
     * other block and updates the current block with the sum of the current and other value.
     */
    fun aggregate(otherBlock: NodeStatsBlock) {
        otherBlock.stats.forEach { (name, value) ->
            val existingValue = stats[name]
            // We only aggregate numbers, and we "only" handle Long and Double because we've already
            // promoted them when adding. For other value types, we override with the new one.
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
                else -> stats[name] = value
            }
        }
        otherBlock.compoundStats.forEach { (name, function) ->
            addCompoundValue(name, function)
        }
        stats[AGGREGATES] = (stats.getOrDefault(AGGREGATES, 0L) as Long) + 1
    }

    /**
     * Promotes integer values to [Long] and floating point values to [Double]. Returns a
     * [Long], [Double], or null.
     */
    private fun promote(n: Number): Number? = when (n) {
        is Byte, is Short, is Int, is Long -> n.toLong()
        is Float, is Double -> n.toDouble()
        else -> null
    }

    fun prettyPrint(indentLevel: Int = 0): String {
        return with(StringBuffer()) {
            appendLnIndent(indentLevel, name)
            stats.forEach { (statName, statValue) ->
                when (statValue) {
                    is NodeStatsBlock -> {
                        appendln(statValue.prettyPrint(indentLevel + 2))
                    }
                    else -> {
                        // statValue is Any, so we know it's non-null
                        appendLnIndent(indentLevel + 2, "$statName: $statValue")
                    }
                }
            }
            compoundStats.forEach { (statName, function) ->
                val statValue = function.invoke(this@NodeStatsBlock)
                appendLnIndent(indentLevel + 2, "$statName: $statValue")
            }
            toString()
        }
    }

    /**
     * Returns a JSON representation of this [NodeStatsBlock].
     */
    fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
        stats.forEach { (name, value) ->
            when (value) {
                is NodeStatsBlock -> put(name, value.toJson())
                else -> put(name, value)
            }
        }
        compoundStats.forEach { (name, function) ->
            put(name, function.invoke(this@NodeStatsBlock))
        }
    }

    companion object {
        /**
         * The stat name that we use to could the number of other block aggregated in this one.
         */
        private const val AGGREGATES = "_aggregates"

        /**
         * Creates a [NodeStatsBlock] from a JSON object. It's shallow and uses only strings.
         */
        fun fromJson(name: String, json: JSONObject): NodeStatsBlock = NodeStatsBlock(name).apply {
            json.keys.forEach {
                addString(it!!.toString(), json[it].toString())
            }
        }

        /**
         * Creates a [NodeStatsBlock] from an ordered JSON object. It's shallow and uses only strings.
         */
        fun fromJson(name: String, json: OrderedJsonObject): NodeStatsBlock = NodeStatsBlock(name).apply {
            json.keys.forEach {
                addString(it.toString(), json[it].toString())
            }
        }
    }
}
