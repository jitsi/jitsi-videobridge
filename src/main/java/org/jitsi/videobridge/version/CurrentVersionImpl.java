/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import org.jitsi.utils.version.*;

import java.util.regex.*;

/**
 * Keeps constants for the application version.
 *
 * Note that the constants are modified at build time, so changes to this file
 * must be synchronized with the build system.
 *
 * @author George Politis
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class CurrentVersionImpl
{
    /**
     * Default version values can be overwritten by the manifest Implementation
     * Version in the format 2.1-build-id.
     */
    private static int parsedMajor = 0;
    private static int parsedMinor = 1;
    private static String parsedBuildId = null;

    static {
        String version = CurrentVersionImpl.class.getPackage()
            .getImplementationVersion();
        if (version != null)
        {
            Matcher m
                = Pattern.compile("(\\d*)\\.(\\d*)-(.*)").matcher(version);
            if (m.find())
            {
                try
                {
                    parsedMajor = Integer.parseInt(m.group(1));
                }
                catch (NumberFormatException nfe) {}

                try
                {
                    parsedMajor = Integer.parseInt(m.group(2));
                }
                catch (NumberFormatException nfe) {}

                parsedBuildId = m.group(3);
            }
        }
    }

    /**
     * The version major of the current application version. In an example
     * 2.3.1 version string 2 is the version major. The version major number
     * changes when a relatively extensive set of new features and possibly
     * rearchitecturing have been applied to the Jitsi Videobridge.
     */
    public static final int VERSION_MAJOR = parsedMajor;

    /**
     * Returns the version minor of the current application version. In an
     * example 2.3.1 version string 3 is the version minor. The version minor
     * number changes after adding enhancements and possibly new features to a
     * given Jitsi Videobridge version.
     */
    public static final int VERSION_MINOR = parsedMinor;

    /**
     * The version prerelease ID of the current application version.
     */
    public static final String PRE_RELEASE_ID = null;// "beta1";

    /**
     * The nightly build ID. This file is auto-updated by build.xml.
     */
    public static final String NIGHTLY_BUILD_ID
        = parsedBuildId != null ? parsedBuildId : "build.git";

    /**
     * The currently running version.
     */
    public static final Version VERSION
        = new VersionImpl(
                "JVB",
                VERSION_MAJOR,
                VERSION_MINOR,
                NIGHTLY_BUILD_ID,
                PRE_RELEASE_ID);
}
