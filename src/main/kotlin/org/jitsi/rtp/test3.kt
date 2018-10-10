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
package org.jitsi.rtp

abstract class Parent {
    abstract val id: Int
}

open class Child1(
    override var id: Int
) : Parent()

class Child2 : Child1 {
    override var id: Int = 0

    constructor(id: Int) : super(id) {
//        this.id = id
    }

    fun parentId(): Int = super.id
}

fun main(args: Array<String>) {
    val c2 = Child2(42)
    println(c2.id)
    println(c2.parentId())
}




//open class Child1 : Parent {
//    override var id: Int
//        set(value: Int) {
//            println("Setting id in Child1 to $value")
//        }
//
//    constructor(id: Int) {
//        this.id = id
//    }
//}
//
//class Child2 : Child1 {
//    override var id: Int = 0
//        set(value: Int) {
//            println("Setting id in Child2 to $value")
//        }
//
//    constructor(id: Int) : super(id)
//}

