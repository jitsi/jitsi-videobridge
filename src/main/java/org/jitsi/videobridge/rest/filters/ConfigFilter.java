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

import org.jitsi.videobridge.rest.annotations.*;
import org.jitsi.videobridge.util.*;

import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.container.*;
import javax.ws.rs.core.*;

/**
 * A filter which returns 404 not found for any path which:
 * 1) Is marked with the {@link EnabledByConfig} annotation and
 * 2) The value corresponding to the provided configuration key
 * is either not present or returns a value of false.
 */
@PreMatching
public class ConfigFilter implements ContainerRequestFilter
{
    @Context
    protected ResourceInfo resourceInfo;

    @Inject
    ConfigProvider configProvider;

    @Override
    public void filter(ContainerRequestContext containerRequestContext)
    {
        if (resourceInfo.getResourceClass().isAnnotationPresent(EnabledByConfig.class))
        {
            EnabledByConfig anno = resourceInfo.getResourceClass().getAnnotation(EnabledByConfig.class);
            if (!(configProvider.get().getBoolean(anno.value(), false)))
            {
                throw new NotFoundException();
            }
        }
    }
}
