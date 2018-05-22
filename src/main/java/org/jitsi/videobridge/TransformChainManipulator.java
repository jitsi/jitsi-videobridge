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
package org.jitsi.videobridge;

import org.jitsi.impl.neomedia.transform.TransformEngine;

/**
 * An interface for video transform chain manipulation
 *
 * You can register TransformChainManipulator through OSGi.
 *
 * @author Laszlo Luczo
 * @author Norbert Papp
 */
public interface TransformChainManipulator
{

    /**
     * Manipulate transform engines and returns the new engine chain.
     * Called when {@link RtpChannel} creates a new transform chain.
     *
     * @param transformEngines transform engine chain
     * @param channel channel associated to transform engine
     * @return manipulated transform engine chain
     */
    TransformEngine[] manipulate(
            TransformEngine[] transformEngines,
            Channel channel);
}
