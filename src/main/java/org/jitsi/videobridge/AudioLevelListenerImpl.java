/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;

public class AudioLevelListenerImpl
    implements AudioLevelListener
{
    private final ConferenceSpeechActivity conferenceSpeechActivity;

    public AudioLevelListenerImpl(@NotNull ConferenceSpeechActivity conferenceSpeechActivity)
    {
        this.conferenceSpeechActivity = conferenceSpeechActivity;
    }

    @Override
    public void onLevelReceived(long sourceSsrc, long level)
    {
        conferenceSpeechActivity.levelChanged(sourceSsrc, (int)level);
    }
}
