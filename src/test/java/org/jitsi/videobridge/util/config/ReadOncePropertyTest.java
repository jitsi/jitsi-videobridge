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

package org.jitsi.videobridge.util.config;

import com.typesafe.config.*;
import org.jitsi.testutils.*;
import org.jitsi.utils.collections.*;
import org.jitsi.videobridge.util.*;
import org.junit.*;

import static org.jitsi.videobridge.util.config.ReadOncePropertyTest.AnswerProp.*;
import static org.junit.Assert.*;

public class ReadOncePropertyTest
{
    @Test
    public void onlyReadOnce()
    {
        Config legacyConfig = ConfigFactory.parseString(PROP_KEY + "=42");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNoNewConfig()
            .finishSetup();

        AnswerProp answerProp = new AnswerProp();
        assertEquals(42, (int)answerProp.get());

        Config changedLegacyConfig = ConfigFactory.parseString(PROP_KEY + "=32");
        new ConfigSetup()
            .withLegacyConfig(changedLegacyConfig)
            .withNoNewConfig()
            .finishSetup();

        assertEquals(42, (int)answerProp.get());
    }

    protected static class AnswerProp extends ReadOnceProperty<Integer>
    {
        protected static final String PROP_KEY="the.answer";

        protected AnswerProp()
        {
            super(JList.of(
                () -> JvbConfig.getLegacyConfig().getInt(PROP_KEY),
                () -> JvbConfig.getConfig().getInt(PROP_KEY)
            ));
        }
    }
}
