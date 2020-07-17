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

package org.jitsi.videobridge.rest.root.colibri;

import org.jitsi.videobridge.rest.annotations.*;

/**
 * A parent resource from which ALL resources on
 * /colibri/ paths MUST inherit.  The inheritance
 * is required such that we can enforce all sub-paths
 * on /colibri/ are gated by the {@link Constants#ENABLE_REST_COLIBRI_PNAME}
 * config value
 */
@EnabledByConfig(value = Constants.ENABLE_REST_COLIBRI_PNAME, defaultValue = true)
public class ColibriResource
{
}
