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
package org.jitsi.videobridge.rest.ssi;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;

/**
 * A wrapper around HttpServletResponse instance which has custom output stream
 * which just stores into memory the bytes to be served as response.
 * Later the result can be filtered or modified by ssi impl.
 *
 * @author Damian Minkov
 */
public class HttpServletResponseWrapper
    implements HttpServletResponse
{
    /**
     * The response object we wrap.
     */
    private final HttpServletResponse servletResponse;

    /**
     * Construct HttpServletResponse wrapper.
     * @param servletResponse the response to wrap.
     */
    public HttpServletResponseWrapper(
        HttpServletResponse servletResponse)
    {
        this.servletResponse = servletResponse;
    }

    /**
     * Custom ServletOutputStream instance used to store the byte content of
     * the resulting file to serve.
     */
    private CustomServletOutputStream customServletOutputStream;

    /**
     * Custom ServletOutputStream used to store the byte content of
     * the resulting file to serve.
     */
    private class CustomServletOutputStream
        extends ServletOutputStream
    {
        /**
         * Used to store the content of the file to serve before processing it
         * searching for server-side includes.
         */
        private ByteArrayOutputStream resContent = new ByteArrayOutputStream();

        /**
         * Just stores the data into the local byte array.
         * @param b data to be written
         * @throws IOException no exception
         */
        @Override
        public void write(int b)
            throws IOException
        {
            resContent.write(b);
        }

        /**
         * Just a wrapper which is always ready for receiving data.
         * @return
         */
        @Override
        public boolean isReady()
        {
            return true;
        }

        /**
         * Not used.
         * @param writeListener
         */
        @Override
        public void setWriteListener(WriteListener writeListener)
        {}
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException
    {
        if(customServletOutputStream == null)
            customServletOutputStream = new CustomServletOutputStream();
        return customServletOutputStream;
    }

    /**
     * Returns the currently stored file content.
     * @return the currently stored file content.
     */
    byte[] getContent()
    {
        if(customServletOutputStream == null)
            return null;

        return customServletOutputStream.resContent.toByteArray();
    }

    /**
     * Start of wrapped methods for HttpServletResponse.
     */

    @Override
    public void addCookie(Cookie cookie)
    {
        this.servletResponse.addCookie(cookie);
    }

    @Override
    public boolean containsHeader(String s)
    {
        return this.servletResponse.containsHeader(s);
    }

    @Override
    public String encodeURL(String s)
    {
        return this.servletResponse.encodeURL(s);
    }

    @Override
    public String encodeRedirectURL(String s)
    {
        return this.servletResponse.encodeRedirectURL(s);
    }

    @Override
    public String encodeUrl(String s)
    {
        return this.servletResponse.encodeURL(s);
    }

    @Override
    public String encodeRedirectUrl(String s)
    {
        return this.servletResponse.encodeRedirectUrl(s);
    }

    @Override
    public void sendError(int i, String s)
        throws IOException
    {
        this.servletResponse.sendError(i, s);
    }

    @Override
    public void sendError(int i)
        throws IOException
    {
        this.servletResponse.sendError(i);
    }

    @Override
    public void sendRedirect(String s)
        throws IOException
    {
        this.servletResponse.sendRedirect(s);
    }

    @Override
    public void setDateHeader(String s, long l)
    {
        this.servletResponse.setDateHeader(s, l);
    }

    @Override
    public void addDateHeader(String s, long l)
    {
        this.servletResponse.addDateHeader(s, l);
    }

    @Override
    public void setHeader(String s, String s1)
    {
        this.servletResponse.setHeader(s, s1);
    }

    @Override
    public void addHeader(String s, String s1)
    {
        this.servletResponse.addHeader(s, s1);
    }

    @Override
    public void setIntHeader(String s, int i)
    {
        this.servletResponse.setIntHeader(s, i);
    }

    @Override
    public void addIntHeader(String s, int i)
    {
        this.servletResponse.addIntHeader(s, i);
    }

    @Override
    public void setStatus(int i)
    {
        this.servletResponse.setStatus(i);
    }

    @Override
    public void setStatus(int i, String s)
    {
        this.servletResponse.setStatus(i, s);
    }

    @Override
    public int getStatus()
    {
        return this.servletResponse.getStatus();
    }

    @Override
    public String getHeader(String s)
    {
        return this.servletResponse.getHeader(s);
    }

    @Override
    public Collection<String> getHeaders(String s)
    {
        return this.servletResponse.getHeaders(s);
    }

    @Override
    public Collection<String> getHeaderNames()
    {
        return this.servletResponse.getHeaderNames();
    }

    @Override
    public String getCharacterEncoding()
    {
        return this.servletResponse.getCharacterEncoding();
    }

    @Override
    public String getContentType()
    {
        return this.servletResponse.getContentType();
    }

    @Override
    public PrintWriter getWriter()
        throws IOException
    {
        return this.servletResponse.getWriter();
    }

    @Override
    public void setCharacterEncoding(String s)
    {
        this.servletResponse.setCharacterEncoding(s);
    }

    @Override
    public void setContentLength(int i)
    {
        this.servletResponse.setContentLength(i);
    }

    @Override
    public void setContentLengthLong(long l)
    {
        setContentLengthLong(l);
    }

    @Override
    public void setContentType(String s)
    {
        this.servletResponse.setContentType(s);
    }

    @Override
    public void setBufferSize(int i)
    {
        this.servletResponse.setBufferSize(i);
    }

    @Override
    public int getBufferSize()
    {
        return this.servletResponse.getBufferSize();
    }

    @Override
    public void flushBuffer()
        throws IOException
    {
        this.servletResponse.flushBuffer();
    }

    @Override
    public void resetBuffer()
    {
        this.servletResponse.resetBuffer();
    }

    @Override
    public boolean isCommitted()
    {
        return this.servletResponse.isCommitted();
    }

    @Override
    public void reset()
    {
        this.servletResponse.reset();
    }

    @Override
    public void setLocale(Locale locale)
    {
        this.servletResponse.setLocale(locale);
    }

    @Override
    public Locale getLocale()
    {
        return this.servletResponse.getLocale();
    }
}
