package org.jitsi.nlj.stats

import org.jitsi.nlj.util.appendLnIndent

class NodeStatsBlock(val name: String) {
    val stats = mutableMapOf<String, Any?>()

    fun addStat(name: String, value: Any)
    {
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
        return with (StringBuffer()) {
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