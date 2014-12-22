/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.utils;

import org.jitsi.util.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

/**
* @author George Politis
 *
 * TODO(gp) move to a more suitable place
*/
public class StringCompiler
{
    private final Map<String, Object> bindings;

    public StringCompiler(Map<String, Object> bindings)
    {
        this.bindings = bindings;
    }

    public StringCompiler()
    {
        this.bindings = new HashMap<String, Object>();
    }

    public void bind(String key, Object value)
    {
        this.bindings.put(key, value);
    }

    private static final Pattern p = Pattern.compile("\\{([^\\}]+)\\}");

    private StringBuffer sb;

    public StringCompiler c(String s)
    {
        if (StringUtils.isNullOrEmpty(s))
        {
            return this;
        }

        Matcher m = p.matcher(s);
        sb = new StringBuffer();

        while (m.find()) {
            String key = m.group(1);
            String value = getValue(key);
            m.appendReplacement(sb, value);
        }

        m.appendTail(sb);

        return this;
    }

    @Override
    public String toString()
    {
        return (sb == null) ? "" : sb.toString();
    }

    private String getValue(String key)
    {
        if (StringUtils.isNullOrEmpty(key))
            throw new IllegalArgumentException("key");

        String value = "";

        String[] path = key.split("\\.");

        // object graph frontier.
        Object obj = null;

        for (int i = 0; i < path.length; i++)
        {
            String identifier = path[i];

            if (i == 0)
            {
                // Init.
                if (bindings.containsKey(identifier))
                {
                    obj = bindings.get(identifier);
                }
                else
                {
                    break;
                }
            }
            else
            {
                if (obj != null)
                {
                    // Decent the object graph.
                    Class<?> c = obj.getClass();
                    Field f;

                    try
                    {
                        f = c.getDeclaredField(identifier);
                    }
                    catch (NoSuchFieldException e)
                    {
                        break;
                    }

                    if (!f.isAccessible())
                    {
                        f.setAccessible(true);
                    }

                    try
                    {
                        obj = f.get(obj);
                    }
                    catch (IllegalAccessException e)
                    {
                        break;
                    }
                }
                else
                {
                    break;
                }
            }

            if (i == path.length - 1)
            {
                // Stop the decent.
                if (obj != null)
                {
                    value = obj.toString();
                }
            }
        }

        return value;
    }
}
