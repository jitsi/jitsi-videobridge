/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import org.jitsi.utils.logging2.Logger

/** Test scenario column printer,
 * based on WebRTC test/scenario/column_printer.{h,cc} in
 * WebRTC tag branch-heads/6422 (Chromium 125).
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */

class ColumnPrinter(
    val headers: String,
    val printer: (StringBuilder) -> Unit,
    val maxLength: Long = 256
)

class StatesPrinter(
    val logger: Logger,
    val printers: List<ColumnPrinter>
) {
    fun printHeaders() {
        TODO()
    }

    fun printRow() {
        TODO()
    }
}
