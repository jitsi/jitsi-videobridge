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

package org.jitsi.videobridge.rest;

import org.jitsi.utils.collections.*;
import org.jitsi.utils.config.*;
import org.jitsi.videobridge.stats.config.*;
import org.jitsi.videobridge.util.config.*;

//NOTE(brian): temporary to illustrate how making a property obsolete would wokr
// and be caught by validateConfig in Main
@ObsoleteConfig("This property is no longer used, see ReplacementProperty")
public class DummyObsoleteProperty extends ConfigPropertyImpl<String>
{
    protected static final String legacyPropKey = "some.old.prop.key";

    protected DummyObsoleteProperty()
    {
        super(new JvbPropertyConfig<String>()
            .fromLegacyConfig(config -> config.getString(legacyPropKey))
            .readOnce()
            .throwIfNotFound()
        );
    }

    @Override
    public String get()
    {
        return super.get();
    }
}
