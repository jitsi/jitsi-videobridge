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

package org.jitsi.videobridge.rest.annotations;

import java.lang.annotation.*;

/**
 * An annotation which allows a REST resource to be enabled by
 * a property value in config.  'value' should be set to the String
 * key of a boolean stored in a config.  'defaultValue' can be provided
 * to give a default value if the 'value' key is not present in the
 * config (false by default).
 *
 * See {@link org.jitsi.videobridge.rest.filters.ConfigFilter}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface EnabledByConfig
{
    String value();

    boolean defaultValue() default false;
}
