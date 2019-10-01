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

package org.jitsi.videobridge.rest.root.colibri;

import org.junit.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;

import javax.ws.rs.*;

import static org.junit.Assert.*;

public class ColibriResourceTest
{
    /**
     * the /colibri path is enabled via a config parameter, so all resources on
     * that path must extend ColibriResource so we can ensure they are only
     * enabled if /colibri is enabled.
     */
    @Test
    public void testAllColibriResourcesInheritFromColibriResource()
    {
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage("org.jitsi.videobridge.rest.root.colibri"))
                .filterInputsBy(new FilterBuilder().includePackage("org.jitsi.videobridge.rest.root.colibri"))
                .setScanners(new SubTypesScanner(false), new TypeAnnotationsScanner()));

        for (Class<?> clazz : reflections.getTypesAnnotatedWith(Path.class))
        {
            boolean derivesFromColibriResource = false;
            Class<?> superClass = clazz.getSuperclass();
            while ((superClass != null) && (superClass != Object.class))
            {
                if (superClass == ColibriResource.class)
                {
                    derivesFromColibriResource = true;
                    break;
                }
                superClass = superClass.getSuperclass();
            }
            assertTrue(clazz + " must derive from ColibriResource", derivesFromColibriResource);
        }
    }

}