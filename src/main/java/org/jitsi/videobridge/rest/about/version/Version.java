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

package org.jitsi.videobridge.rest.about.version;

import com.fasterxml.jackson.annotation.*;
import org.jitsi.videobridge.util.*;

import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("/")
public class Version
{
    protected final VersionServiceProvider versionServiceProvider;

    public Version(VersionServiceProvider versionServiceProvider)
    {
        this.versionServiceProvider = versionServiceProvider;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public VersionInfo getVersion()
    {
        org.jitsi.utils.version.Version version = versionServiceProvider.get().getCurrentVersion();

        return new VersionInfo(
                version.getApplicationName(),
                version.toString(),
                System.getProperty("os.name")
        );
    }

    static class VersionInfo {
        @JsonProperty String name;
        @JsonProperty String version;
        @JsonProperty String os;

        public VersionInfo() {}
        public VersionInfo(String name, String version, String os)
        {
            this.name = name;
            this.version = version;
            this.os = os;
        }
    }
}
