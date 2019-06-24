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

package org.jitsi.videobridge.stats;

import java.util.concurrent.atomic.*;

public class DoubleAverage
{
    private final DoubleAdder total = new DoubleAdder();
    private final LongAdder count = new LongAdder();
    public final String name;

    public DoubleAverage(String name)
    {
        this.name = name;
    }

    public void addValue(double value) {
        total.add(value);
        count.increment();
    }

    public double get() {
        return total.sum() / (double)count.sum();
    }
}
