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

import io.kotlintest.IsolationMode
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.util.PacketPredicate

internal class NodeTeardownVisitorTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val testIncomingPipeline = pipeline {
        simpleNode("Node 1") { pkts -> pkts }
        demux("Node 2") {
            packetPath {
                name = "Node 2 path 1"
                predicate = PacketPredicate { true }
                path = pipeline {
                    simpleNode("Node 2 path 1 Node 1") { pkts -> pkts }
                    simpleNode("Node 2 path 1 Node 2") { pkts -> pkts }
                }
            }
            packetPath {
                name = "Node 2 path 2"
                predicate = PacketPredicate { true }
                path = pipeline {
                    simpleNode("Node 2 path 2 Node 1") { pkts -> pkts }
                    simpleNode("Node 2 path 2 Node 2") { pkts -> pkts }
                }
            }
        }
    }

    // 'Outgoing' style is harder to define: we actually need multiple separate pipeline that
    // terminate at the same node
    private val testOutgoingPipelineTermination = object : ConsumerNode("Output termination") {
        override fun consume(packetInfo: PacketInfo) {}
    }

    private val testOutgoingPipeline1 = pipeline {
        simpleNode("OutgoingPipeline 1 Node 1") { pkts -> pkts }
        simpleNode("OutgoingPipeline 1 Node 2") { pkts -> pkts }
        simpleNode("OutgoingPipeline 1 Node 3") { pkts -> pkts }
        node(testOutgoingPipelineTermination)
    }

    private val testOutgoingPipeline2 = pipeline {
        simpleNode("OutgoingPipeline 2 Node 1") { pkts -> pkts }
        simpleNode("OutgoingPipeline 2 Node 2") { pkts -> pkts }
        simpleNode("OutgoingPipeline 2 Node 3") { pkts -> pkts }
        node(testOutgoingPipelineTermination)
    }

    init {
        "tearing down an 'incoming-style' pipeline" {
            NodeTeardownVisitor().visit(testIncomingPipeline)
        }
        "tearing down an 'outgoing-style' pipeline" {
            NodeTeardownVisitor().reverseVisit(testOutgoingPipelineTermination)
        }
    }
}
