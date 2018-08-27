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
package org.jitsi.service.neomedia;

/**
 * The <tt>MediaType</tt> enumeration contains a list of media types
 * currently known to and handled by the <tt>MediaService</tt>.
 *
 * @see <a href="http://www.iana.org/assignments/sdp-parameters/sdp-parameters.xhtml#sdp-parameters-1">
 *     Session Description Protocol (SDP) Parameters, media</a>
 *
 *
 * @author Emil Ivov
 */
public enum MediaType
{
    /**
     * Represents an AUDIO media type.
     */
    AUDIO("audio"),

    /**
     * Represents a VIDEO media type.
     */
    VIDEO("video"),

    /**
     * Represents a TEXT media type. See RFC4103.
     */
    TEXT("text"),

    /**
     * Represents an APPLICATION media type.
     */
    APPLICATION("application"),

    /**
     * Represents a (chat-) MESSAGE media type.
     */
    MESSAGE("message"),

    /**
     * Represents an IMAGE media type. See RFC6466.
     */
    IMAGE("image"),

    /**
     * Represents a DATA media type.
     * @deprecated In RFC4566. Still defined to avoid parsing errors.
     */
    @Deprecated
    CONTROL("control"),

    /**
     * Represents a DATA media type.
     * @deprecated In RFC4566. Still defined to avoid parsing errors.
     */
    @Deprecated
    DATA("data");

    /**
     * The name of this <tt>MediaType</tt>.
     */
    private final String mediaTypeName;

    /**
     * Creates a <tt>MediaType</tt> instance with the specified name.
     *
     * @param mediaTypeName the name of the <tt>MediaType</tt> we'd like to
     * create.
     */
    private MediaType(String mediaTypeName)
    {
        this.mediaTypeName = mediaTypeName;
    }

    /**
     * Returns the name of this MediaType (e.g. "audio", "message" or "video").
     * The name returned by this method is meant for use by session description
     * mechanisms such as SIP/SDP or XMPP/Jingle.
     *
     * @return the name of this MediaType (e.g. "audio" or "video").
     */
    @Override
    public String toString()
    {
        return mediaTypeName;
    }

    /**
     * Returns a <tt>MediaType</tt> value corresponding to the specified
     * <tt>mediaTypeName</tt> or in other words <tt>AUDIO</tt>, <tt>MESSAGE</tt>
     * or <tt>VIDEO</tt>.
     *
     * @param mediaTypeName the name that we'd like to parse.
     * @return a <tt>MediaType</tt> value corresponding to the specified
     * <tt>mediaTypeName</tt>.
     *
     * @throws IllegalArgumentException in case <tt>mediaTypeName</tt> is not a
     * valid or currently supported media type.
     */
    public static MediaType parseString(String mediaTypeName)
            throws IllegalArgumentException
    {
        if(AUDIO.toString().equalsIgnoreCase(mediaTypeName))
            return AUDIO;

        if(VIDEO.toString().equalsIgnoreCase(mediaTypeName))
            return VIDEO;

        if(TEXT.toString().equalsIgnoreCase(mediaTypeName))
            return TEXT;

        if(APPLICATION.toString().equalsIgnoreCase(mediaTypeName))
            return APPLICATION;

        if(MESSAGE.toString().equalsIgnoreCase(mediaTypeName))
            return MESSAGE;

        if(IMAGE.toString().equalsIgnoreCase(mediaTypeName))
            return IMAGE;

        if(CONTROL.toString().equalsIgnoreCase(mediaTypeName))
            return CONTROL;

        if(DATA.toString().equalsIgnoreCase(mediaTypeName))
            return DATA;

        throw new IllegalArgumentException(
                mediaTypeName + " is not a currently supported MediaType");
    }
}
