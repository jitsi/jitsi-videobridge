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

package org.jitsi.videobridge.util.config;

import org.jitsi.utils.config.*;
import org.jitsi.utils.logging2.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

public class ConfigValidator
{
    protected Logger logger = new LoggerImpl(getClass().getName());
    protected final Reflections reflections;

    public ConfigValidator(String packageName)
    {
        reflections = new Reflections(new ConfigurationBuilder()
            .setUrls(ClasspathHelper.forPackage(packageName))
            .setScanners(
                new SubTypesScanner(),
                new TypeAnnotationsScanner()
            )
        );
    }

    public void validate()
    {
        checkForDefinedObsoleteProperties();
    }

    protected Set<Class<? extends ConfigProperty>> getConfigProperties()
    {
        return reflections.getSubTypesOf(ConfigProperty.class);
    }

    protected void checkForDefinedObsoleteProperties()
    {
        Set<Class<? extends ConfigProperty>> obsoleteConfigProperties = getConfigProperties()
            .stream()
            .filter(clazz -> clazz.isAnnotationPresent(ObsoleteConfig.class))
            .collect(Collectors.toSet());

        for (Class<? extends ConfigProperty> obsoleteConfigProperty : obsoleteConfigProperties)
        {
            if (Modifier.isAbstract(obsoleteConfigProperty.getModifiers()))
            {
                continue;
            }
            try
            {
                Constructor<? extends ConfigProperty> ctor = obsoleteConfigProperty.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object value = ctor.newInstance().get();
                ObsoleteConfig anno = obsoleteConfigProperty.getAnnotation(ObsoleteConfig.class);
                logger.warn("Prop " + obsoleteConfigProperty + " is obsolete but was present in config with " +
                    "value '" + value.toString() + "': " + anno.value());
            }
            catch (NoSuchMethodException e)
            {
                logger.error("Configuration property " + obsoleteConfigProperty + " must have a no-arg constructor!");
            }
            catch (InvocationTargetException e)
            {
                // We don't get a raw ConfigPropertyNotFoundException
                // when calling it this way, instead it's wrapped by
                // an InvocationTargetException
                if (e.getCause() instanceof ConfigPropertyNotFoundException)
                {
                    logger.debug("Prop " + obsoleteConfigProperty + " is obsolete but wasn't found defined, ok!");
                }
                else
                {
                    logger.debug("Error creating instance of " + obsoleteConfigProperty + ": " + e.toString());
                }
            }
            catch (InstantiationException | IllegalAccessException e)
            {
                logger.error("Error creating instance of " + obsoleteConfigProperty + ": " + e.toString());
            }
        }
    }
}
