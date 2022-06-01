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

package org.jitsi.nlj.module_tests

import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.test_utils.SrtpData

class SrtpTransformerFactory {
    companion object {
        fun createSrtpTransformers(srtpData: SrtpData): SrtpTransformers {
            return SrtpUtil.initializeTransformer(
                srtpData.srtpProfileInformation,
                srtpData.keyingMaterial,
                srtpData.tlsRole,
                StdoutLogger()
            )
        }
    }
}
