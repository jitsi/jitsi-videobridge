/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

import org.jitsi.util.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

/**
* Created by gp on 27/10/14.
*/
class StringCompiler
{
    private final Map<String, Object> map;

    public StringCompiler(Map<String, Object> map)
    {
        this.map = map;
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
                obj = map.get(identifier);
            }
            else
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
