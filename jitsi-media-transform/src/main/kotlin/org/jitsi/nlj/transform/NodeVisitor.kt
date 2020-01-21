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

package org.jitsi.nlj.transform

import org.jitsi.nlj.Event
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.DemuxerNode
import org.jitsi.nlj.transform.node.Node

abstract class NodeVisitor {
    open fun visit(node: Node) {
        doWork(node)
        node.getChildren().forEach { this.visit(it) }
    }

    /**
     * [reverseVisit] is used for traversing an 'outgoing'-style tree which
     * has many input paths but terminates at a single point (as opposed to an
     * 'incoming'-style tree which starts at a single point and then branches
     * into multiple paths.  With reverseVisit, we start at the single terminating
     * point and traverse backwards through the tree.  It should be noted, however,
     * that reverseVisit is done in a 'postorder' traversal style (meaning that a [Node]'s
     * 'inputNodes' are visited before that Node itself.
     * TODO: protect against visiting the same node twice in the event of a cycle (which
     * we should do for 'visit' as well)
     */
    open fun reverseVisit(node: Node) {
        // NOTE(brian): although we're doing a reverse visit here, we still visit the nodes
        // in 'forward' order by going up through all the parents first and then calling
        // doWork on our way back 'down', as it's usually more useful to do things like
        // view the stats in the 'normal' order and just use the 'reverse' visit as a
        // way to handle the 'outgoing' tree style (as mentioned in the comment above)
        node.getParents().forEach { this.reverseVisit(it) }
        doWork(node)
    }
    protected abstract fun doWork(node: Node)
}

class NodeStatsVisitor(val nodeStatsBlock: NodeStatsBlock) : NodeVisitor() {
    override fun doWork(node: Node) {
        val block = node.getNodeStats()
        nodeStatsBlock.addBlock(block)
    }
}

class NodeEventVisitor(val event: Event) : NodeVisitor() {
    override fun doWork(node: Node) {
        node.handleEvent(event)
    }
}

/**
 * Produces a set of all notes in the tree.
 */
class NodeSetVisitor(val nodeSet: MutableSet<Node> = mutableSetOf()) : NodeVisitor() {
    override fun doWork(node: Node) {
        nodeSet.add(node)
    }
}

class NodeTeardownVisitor : NodeVisitor() {
    override fun doWork(node: Node) {
        node.stop()
        when (node) {
            is DemuxerNode -> node.removePacketPaths()
            else -> node.detachNext()
        }
    }

    override fun visit(node: Node) {
        node.getChildren().forEach { this.visit(it) }
        doWork(node)
    }

    override fun reverseVisit(node: Node) {
        // We can't use the default reverseVisit method, because in doWork
        // we modify the parents list, so we'll get a ConcurrentModificationException.
        // So instead we override the method here and make a copy of the parents
        // and use that to iterate over so we can modify the real one
        val parentsCopy = node.getParents().toList()
        parentsCopy.forEach { this.reverseVisit(it) }
        doWork(node)
    }
}
