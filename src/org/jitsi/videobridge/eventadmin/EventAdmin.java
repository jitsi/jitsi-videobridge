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
package org.jitsi.videobridge.eventadmin;

/**
 * The Event Admin service. Bundles wishing to publish events must obtain the
 * Event Admin service and call the event delivery method.
 *
 * @author George Politis
 */
public interface EventAdmin
{
    /**
     * Initiate synchronous delivery of an event. This method does not return to
     * the caller until delivery of the event is completed.
     *
     * @param event The event to send to all listeners which subscribe to the
     * topic of the event.
     */
    void sendEvent(Event event);
}
