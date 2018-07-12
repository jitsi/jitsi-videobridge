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
package org.jitsi.nlj.experiment

//open class Pkt
//class RtpPkt : Pkt()
//class RtcpPkt : Pkt()
//
//abstract class Node2<out InputPacketType : Pkt, out OutputPacketType : Pkt> {
//}
//class RtpNode2 : Node2<Pkt, RtpPkt>()
//class RtcpNode2 : Node2<Pkt, RtcpPkt>()
//
//fun doThing(n: Node2<Pkt, Pkt>) {
//}
//doThing(RtcpNode2())


open class Shape
open class Oval : Shape()
class Circle : Oval()
open class Quadrilateral : Shape()
class Square : Quadrilateral()

class NodeChain<ShapeType : Shape> {
    val nodes: MutableList<Node<in ShapeType, out Shape>> = mutableListOf()

    fun node(n: Node<in ShapeType, out Shape>) {
        println("adding node")
        val prevNode = nodes.lastOrNull()
        nodes.add(n)
//        prevNode?.nextNode = n::processInput
    }

    fun processInput(input: List<ShapeType>) {
        println("Chain processing input")
        nodes[0].processInput(input)
    }
}

abstract class Node<InputType : Shape, OutputType : Shape> {
    var nextNode: Function1<List<OutputType>, Unit>? = null

    abstract fun processInput(input: List<InputType>)
}

class OvalNode : Node<Shape, Oval>() {
    override fun processInput(input: List<Shape>) {
        println("OvalNode processing shape")
        nextNode?.invoke(listOf(Oval()))
    }
}

class CircleNode : Node<Oval, Circle>() {
    override fun processInput(input: List<Oval>) {
        println("CircleNode processing oval")
        nextNode?.invoke(listOf(Circle()))
    }
}

class NodeChain2 {
    val nodes = mutableListOf<Node2>()

    fun node(n: Node2) {
        val prevNode = nodes.lastOrNull()
        nodes.add(n)
        prevNode?.nextNode = n::processInput
    }

    fun processInput(input: List<Shape>) {
        nodes[0].processInput(input)
    }
}

abstract class Node2 {
    var nextNode: ((List<Shape>) -> Unit)? = null

    abstract fun processInput(input: List<Shape>)
}

class OvalNode2 : Node2() {
    override fun processInput(input: List<Shape>) {
        println("OvalNode2 process")
        nextNode?.invoke(listOf(Oval()))
    }
}

class CircleNode2 : Node2() {
    override fun processInput(input: List<Shape>) {
        println("CircleNode2 process")
        input.forEach {
            if (it is Oval) {
                println("CircleNode2 got Oval")
                nextNode?.invoke(listOf(Circle()))
            } else {
                throw Exception()
            }
        }
    }
}

//class QuadrilateralNode : Node<Shape, Quadrilateral>() {
//    override fun processInput(input: List<Shape>) {
//        nextNode?.processInput(listOf(Quadrilateral()))
//    }
//}
//
//class SquareNode : Node<Quadrilateral, Square>() {
//    override fun processInput(input: List<Quadrilateral>) {
//
//    }
//}


//nodeChain.processInput(listOf(Shape()))


//val ovalNode = OvalNode()
//val circleNode = CircleNode()
//ovalNode.nextNode = circleNode::processInput

////val quadNode = QuadrilateralNode()
//
//ovalNode.nextNode = circleNode::processInput
//
//ovalNode.processInput(listOf(Shape()))
//
//val x = mutableListOf<Node<*, *>>()
//x.add(ovalNode)
//x.add(circleNode)


fun main(args: Array<String>) {
    val nodeChain = with (NodeChain<Circle>()) {
        node(OvalNode())
        node(CircleNode())
//        node(CircleNode() as Node<Shape, Shape>)
        this
    }
//    nodeChain.processInput(listOf(Shape()))

//    val nodeChain2 = NodeChain2().apply {
//        node(CircleNode2())
//    }
//    nodeChain2.processInput(listOf(Shape()))

}
