/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.simulcast;

/**
* Created by gp on 16/10/14.
*/
public class SimulcastReceiverOptions
{

    private Integer nextOrder;

    /**
     * A switch that requires a key frame.
     */
    private boolean hardSwitch;

    private Integer overrideOrder;

    public void setUrgent(boolean urgent)
    {
        this.urgent = urgent;
    }

    public void setNextOrder(Integer nextOrder)
    {
        this.nextOrder = nextOrder;
    }

    public void setHardSwitch(boolean hardSwitch)
    {
        this.hardSwitch = hardSwitch;
    }

    /**
     * A switch that is urgent (e.g. because of a layer drop).
     */
    private boolean urgent;

    public Integer getNextOrder()
    {
        return nextOrder;
    }

    public boolean isHardSwitch()
    {
        return hardSwitch;
    }

    public boolean isUrgent()
    {
        return urgent;
    }

    public Integer getOverrideOrder()
    {
        return overrideOrder;
    }

    public void setOverrideOrder(Integer overrideOrder)
    {
        this.overrideOrder = overrideOrder;
    }
}
