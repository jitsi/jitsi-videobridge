/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.utils;

import org.jitsi.service.configuration.*;

import java.io.*;

/**
 * @author George Politis
 */
public class ConfigUtils
{
    /**
     * Gets an absolute path in the form of <tt>File</tt> from an absolute or
     * relative <tt>path</tt> specified in the form of a <tt>String</tt>. If
     * <tt>path</tt> is relative, it is resolved against
     * <tt>ConfigurationService.PNAME_SC_HOME_DIR_LOCATION</tt> and
     * <tt>ConfigurationService.PNAME_SC_HOME_DIR_NAME</tt>, <tt>user.home</tt>,
     * or the current working directory.
     *
     * @param path the absolute or relative path in the form of <tt>String</tt>
     * for/from which an absolute path in the form of <tt>File</tt> is to be
     * returned
     * @param cfg the <tt>ConfigurationService</tt> to be employed by the method
     * (invocation) if necessary
     * @return an absolute path in the form of <tt>File</tt> for/from the
     * specified <tt>path</tt>
     */
    public static File getAbsoluteFile(String path, ConfigurationService cfg)
    {
        File file = new File(path);

        if (!file.isAbsolute())
        {
            String scHomeDirLocation, scHomeDirName;

            if (cfg == null)
            {
                scHomeDirLocation
                        = System.getProperty(
                        ConfigurationService.PNAME_SC_HOME_DIR_LOCATION);
                scHomeDirName
                        = System.getProperty(
                        ConfigurationService.PNAME_SC_HOME_DIR_NAME);
            }
            else
            {
                scHomeDirLocation = cfg.getScHomeDirLocation();
                scHomeDirName = cfg.getScHomeDirName();
            }
            if (scHomeDirLocation == null)
            {
                scHomeDirLocation = System.getProperty("user.home");
                if (scHomeDirLocation == null)
                    scHomeDirLocation = ".";
            }
            if (scHomeDirName == null)
                scHomeDirName = ".";
            file
                    = new File(new File(scHomeDirLocation, scHomeDirName), path)
                    .getAbsoluteFile();
        }
        return file;
    }
}
