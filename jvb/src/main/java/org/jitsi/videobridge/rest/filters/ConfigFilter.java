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

package org.jitsi.videobridge.rest.filters;

import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.container.*;
import javax.ws.rs.core.*;

/**
 * A filter which returns 404 not found for any path which:
 * 1) Is marked with the {@link EnabledByConfig} annotation and
 * 2) The resulting config value in {@link RestConfig} is `false`.
 */
public class ConfigFilter implements ContainerRequestFilter
{
    @Context
    protected ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext containerRequestContext)
    {
        Class<?> clazz = resourceInfo.getResourceClass();
        if  (clazz.isAnnotationPresent(EnabledByConfig.class))
        {
            EnabledByConfig anno = clazz.getAnnotation(EnabledByConfig.class);
            if (!RestConfig.config.isEnabled(anno.value()))
            {
                throw new NotFoundException();
            }
        }
    }
}
