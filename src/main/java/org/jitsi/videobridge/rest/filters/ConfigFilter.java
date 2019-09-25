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
public class ConfigFilter implements ContainerRequestFilter
{
    @Context
    protected ResourceInfo resourceInfo;

    @Inject
    ConfigProvider configProvider;

    /**
     * REST paths can be enabled/disabled by config at multiple levels, so it's
     * possible that the specific resource class for the URI may be enabled, but
     * part of its parent path isn't.  For example:
     * /colibri/shutdown, is enabled by
     * {@link org.jitsi.videobridge.rest.root.colibri.shutdown.Constants#ENABLE_REST_SHUTDOWN_PNAME},
     * but the root /colibri path is controlled by
     * {@link org.jitsi.videobridge.rest.root.colibri.Constants#ENABLE_REST_COLIBRI_PNAME}.
     * So when we check if a specific resource should be enabled, we need to walk its
     * entire ancestry to make sure all parent resources are also enabled.  This means that
     * in order to enforce this sort of hierarchy, sub-resources must descend from a common
     * parent (a la {@link  org.jitsi.videobridge.rest.root.colibri.ColibriResource})
     */
    @Override
    public void filter(ContainerRequestContext containerRequestContext)
    {
        Class<?> clazz = resourceInfo.getResourceClass();
        while (!(Object.class == clazz))
        {
            if  (clazz.isAnnotationPresent(EnabledByConfig.class))
            {
                EnabledByConfig anno = clazz.getAnnotation(EnabledByConfig.class);
                if (!(configProvider.get().getBoolean(anno.value(), true)))
                {
                    System.out.println(anno.value() + " is set to false, not allowing path");
                    throw new NotFoundException();
                }
            }
            clazz = clazz.getSuperclass();
        }
    }
}
