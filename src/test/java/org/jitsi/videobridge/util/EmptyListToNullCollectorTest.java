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

package org.jitsi.videobridge.util;

import org.jitsi.utils.collections.*;
import org.junit.*;

import java.util.*;

import static org.junit.Assert.*;

public class EmptyListToNullCollectorTest
{
    @Test
    public void nonEmptyShouldCollectToCorrectList()
    {
        List<Integer> result = JList.of(1, 2, 3)
            .stream()
            .collect(new EmptyListToNullCollector<>());

        assertNotNull(result);
        assertTrue(result.contains(1));
        assertTrue(result.contains(2));
        assertTrue(result.contains(3));
    }

    @Test
    public void emptyShouldCollectToNull()
    {
        List<Integer> result = JList.<Integer>of()
            .stream()
            .collect(new EmptyListToNullCollector<>());

        assertNull(result);
    }
}