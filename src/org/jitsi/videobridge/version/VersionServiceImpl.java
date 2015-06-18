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
package org.jitsi.videobridge.version;

import org.jitsi.service.version.*;
import org.jitsi.service.version.util.*;

import java.util.regex.*;

/**
 * The version service keeps track of the Jitsi version that we are currently
 * running. Other modules query and use this service in order to show the
 * current application version.
 * <p>
 * This version service implementation is based around the VersionImpl class
 * where all details of the version are statically defined.
 *
 * @author Emil Ivov
 * @author George Politis
 */
public class VersionServiceImpl
    implements VersionService
{
    /**
     * The pattern that will parse strings to version object.
     */
    private static final Pattern PARSE_VERSION_STRING_PATTERN =
        Pattern.compile("(\\d+)\\.(\\d+)\\.([\\d\\.]+)");

    /**
     * Returns a Version instance corresponding to the <tt>version</tt>
     * string.
     *
     * @param version a version String that we have obtained by calling a
     *   <tt>Version.toString()</tt> method.
     * @return the <tt>Version</tt> object corresponding to the
     *   <tt>version</tt> string. Or null if we cannot parse the string.
     */
    public Version parseVersionString(String version)
    {
        Matcher matcher = PARSE_VERSION_STRING_PATTERN.matcher(version);

        if(matcher.matches() && matcher.groupCount() == 3)
        {
            return VersionImpl.customVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3));
        }

        return null;
    }

    /**
     * Returns a <tt>Version</tt> object containing version details of the
     * Jitsi Videobridge version that we're currently running.
     *
     * @return a <tt>Version</tt> object containing version details of the
     *   Jitsi Videobridge version that we're currently running.
     */
    public Version getCurrentVersion()
    {
        return VersionImpl.currentVersion();
    }
}
