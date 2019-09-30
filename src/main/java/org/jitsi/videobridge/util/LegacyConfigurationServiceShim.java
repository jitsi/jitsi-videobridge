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

package org.jitsi.videobridge.util;

import com.typesafe.config.*;
import org.jitsi.service.configuration.*;

import java.beans.*;
import java.io.*;
import java.util.*;
import java.util.stream.*;

public class LegacyConfigurationServiceShim implements ConfigurationService
{
    @Override
    public Object getProperty(String s)
    {
        throw new RuntimeException("needed?");
    }

    @Override
    public List<String> getAllPropertyNames()
    {
        return getConfig().entrySet()
                .stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getPropertyNamesByPrefix(String prefix, boolean exactMatch)
    {
        return getAllPropertyNames()
                .stream()
                .filter(propName -> {
                    if (exactMatch)
                    {
                        int index = propName.lastIndexOf(".");
                        if (index == -1)
                        {
                            return false;
                        }
                        String propPrefix = propName.substring(0, index);
                        return prefix.equals(propPrefix);
                    }
                    else
                    {
                        return propName.startsWith(prefix);
                    }
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getPropertyNamesBySuffix(String suffix)
    {
        return getAllPropertyNames()
                .stream()
                .filter(propName -> {
                    int index = propName.lastIndexOf(".");
                    return index != -1 && suffix.equals(propName.substring(index + 1));

                })
                .collect(Collectors.toList());
    }

    @Override
    public String getString(String propName)
    {
        return getConfig().getString(propName);
    }

    private boolean hasProp(String propName)
    {
        return getConfig().hasPath(propName);
    }

    private Config getConfig()
    {
        return JvbConfig.getConfig().getConfig("legacy");
    }

    @Override
    public String getString(String propName, String defaultValue)
    {
        if (hasProp(propName))
        {
            return getConfig().getString(propName);
        }
        return defaultValue;
   }

    @Override
    public boolean getBoolean(String propName, boolean defaultValue)
    {
        if (hasProp(propName))
        {
            return getConfig().getBoolean(propName);
        }
        return defaultValue;
    }

    @Override
    public int getInt(String propName, int defaultValue)
    {
        if (hasProp(propName))
        {
            return getConfig().getInt(propName);
        }
        return defaultValue;
    }

    @Override
    public double getDouble(String propName, double defaultValue)
    {
        if (hasProp(propName))
        {
            return getConfig().getDouble(propName);
        }
        return defaultValue;
    }

    @Override
    public long getLong(String propName, long defaultValue)
    {
        if (hasProp(propName))
        {
            return getConfig().getLong(propName);
        }
        return defaultValue;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener propertyChangeListener)
    {
        throw new RuntimeException("Not supported in Config shim");
    }

    @Override
    public void addPropertyChangeListener(String s, PropertyChangeListener propertyChangeListener)
    {
        throw new RuntimeException("Not supported in Config shim");
    }

    @Override
    public void addVetoableChangeListener(ConfigVetoableChangeListener configVetoableChangeListener)
    {
        throw new RuntimeException("Not supported in Config shim");
    }

    @Override
    public void addVetoableChangeListener(String s, ConfigVetoableChangeListener configVetoableChangeListener)
    {
        throw new RuntimeException("Not supported in Config shim");
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener propertyChangeListener)
    {
        System.out.println("remove prop change listener");
    }

    @Override
    public void removePropertyChangeListener(String s, PropertyChangeListener propertyChangeListener)
    {
        System.out.println("remove prop change listener 2");
    }

    @Override
    public void removeVetoableChangeListener(ConfigVetoableChangeListener configVetoableChangeListener)
    {
        System.out.println("remove vetoable prop change listener");
    }

    @Override
    public void removeVetoableChangeListener(String s, ConfigVetoableChangeListener configVetoableChangeListener)
    {
        System.out.println("remove vetoable prop change listener 2");
    }

    @Override
    public void storeConfiguration() throws IOException
    {
        throw new RuntimeException("Not supported in Config shim");
    }

    @Override
    public void reloadConfiguration() throws IOException
    {
        System.out.println("reload");
        // No op? TODO?
    }

    @Override
    public void purgeStoredConfiguration()
    {
        System.out.println("purge");
        // No op? TODO?
    }

    @Override
    public void logConfigurationProperties(String s)
    {
        System.out.println("log");
        // No op? TODO?
    }

    @Override
    public String getScHomeDirLocation()
    {
        throw new RuntimeException("Not supported in Config shim");
    }

    @Override
    public String getScHomeDirName()
    {
        throw new RuntimeException("Not supported in Config shim");
    }

    @Override
    public String getConfigurationFilename()
    {
        throw new RuntimeException("Not supported in Config shim");
    }

    // Modification is not supported in the shim
    @Override
    public void removeProperty(String s)
    {
        throw new RuntimeException("Config shim doesn't support modification");
    }

    @Override
    public void setProperty(String s, Object o)
    {
        throw new RuntimeException("Config shim doesn't support modification");
    }

    @Override
    public void setProperties(Map<String, Object> map)
    {
        throw new RuntimeException("Config shim doesn't support modification");
    }

    @Override
    public void setProperty(String s, Object o, boolean b)
    {
        throw new RuntimeException("Config shim doesn't support modification");
    }
}
