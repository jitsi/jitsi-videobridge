/*
 * Copyright @ 2021-Present 8x8, Inc
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
package org.jitsi.nlj

enum class VideoType {
    CAMERA,
    DESKTOP,
    DESKTOP_HIGH_FPS,
    DISABLED,
    // NONE was used in the context where an Endpoint has always one media source description. It used to cover both
    // lack of the actual source and the source being temporarily disabled. With the support for multiple sources per
    // endpoint DISABLED means a source is turned off. Lack of a MediaSourceDesc is equivalent to NONE.
    @Deprecated("Use DISABLED instead", ReplaceWith("DISABLED"))
    NONE
}
