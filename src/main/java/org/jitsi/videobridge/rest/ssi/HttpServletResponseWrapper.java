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
package org.jitsi.videobridge.rest.ssi;

import java.io.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;

/**
 * A wrapper around {@code HttpServletResponse} instance which has custom output
 * stream which just stores into memory the bytes to be served as response.
 * Later the result can be filtered or modified by ssi impl.
 *
 * @author Damian Minkov
 */
public class HttpServletResponseWrapper
    implements HttpServletResponse
{
    /**
     * Custom ServletOutputStream instance used to store the byte content of
     * the resulting file to serve.
     */
    private ByteArrayServletOutputStream outputStream;

    /**
     * The response object we wrap.
     */
    private final HttpServletResponse servletResponse;

    /**
     * Constructs a new HttpServletResponse wrapper.
     * @param servletResponse the response to wrap.
     */
    public HttpServletResponseWrapper(HttpServletResponse servletResponse)
    {
        this.servletResponse = servletResponse;
    }

    @Override
    public void addCookie(Cookie cookie)
    {
        servletResponse.addCookie(cookie);
    }

    @Override
    public void addDateHeader(String s, long l)
    {
        servletResponse.addDateHeader(s, l);
    }

    @Override
    public void addHeader(String s, String s1)
    {
        servletResponse.addHeader(s, s1);
    }

    @Override
    public void addIntHeader(String s, int i)
    {
        servletResponse.addIntHeader(s, i);
    }

    @Override
    public boolean containsHeader(String s)
    {
        return servletResponse.containsHeader(s);
    }

    @Override
    public String encodeRedirectURL(String s)
    {
        return servletResponse.encodeRedirectURL(s);
    }

    @Override
    public String encodeRedirectUrl(String s)
    {
        return servletResponse.encodeRedirectUrl(s);
    }

    @Override
    public String encodeURL(String s)
    {
        return servletResponse.encodeURL(s);
    }

    @Override
    public String encodeUrl(String s)
    {
        return servletResponse.encodeURL(s);
    }

    @Override
    public void flushBuffer()
        throws IOException
    {
        servletResponse.flushBuffer();
    }

    @Override
    public int getBufferSize()
    {
        return servletResponse.getBufferSize();
    }

    @Override
    public String getCharacterEncoding()
    {
        return servletResponse.getCharacterEncoding();
    }

    /**
     * Returns the currently stored file content.
     * @return the currently stored file content.
     */
    byte[] getContent()
    {
        return
            (outputStream == null)
                ? null
                : outputStream.resContent.toByteArray();
    }

    @Override
    public String getContentType()
    {
        return servletResponse.getContentType();
    }

    @Override
    public String getHeader(String s)
    {
        return servletResponse.getHeader(s);
    }

    @Override
    public Collection<String> getHeaderNames()
    {
        return servletResponse.getHeaderNames();
    }

    @Override
    public Collection<String> getHeaders(String s)
    {
        return servletResponse.getHeaders(s);
    }

    @Override
    public Locale getLocale()
    {
        return servletResponse.getLocale();
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException
    {
        if (outputStream == null)
            outputStream = new ByteArrayServletOutputStream();
        return outputStream;
    }

    @Override
    public int getStatus()
    {
        return servletResponse.getStatus();
    }

    @Override
    public PrintWriter getWriter()
        throws IOException
    {
        return servletResponse.getWriter();
    }

    @Override
    public boolean isCommitted()
    {
        return servletResponse.isCommitted();
    }

    @Override
    public void reset()
    {
        servletResponse.reset();
    }

    @Override
    public void resetBuffer()
    {
        servletResponse.resetBuffer();
    }

    @Override
    public void setBufferSize(int bufferSize)
    {
        servletResponse.setBufferSize(bufferSize);
    }

    @Override
    public void setCharacterEncoding(String characterEncoding)
    {
        servletResponse.setCharacterEncoding(characterEncoding);
    }

    @Override
    public void setContentLength(int contentLength)
    {
        servletResponse.setContentLength(contentLength);
    }

    @Override
    public void setContentLengthLong(long contentLength)
    {
        servletResponse.setContentLengthLong(contentLength);
    }

    @Override
    public void setContentType(String contentType)
    {
        servletResponse.setContentType(contentType);
    }

    @Override
    public void setDateHeader(String s, long l)
    {
        servletResponse.setDateHeader(s, l);
    }

    @Override
    public void setHeader(String s, String s1)
    {
        servletResponse.setHeader(s, s1);
    }

    @Override
    public void setIntHeader(String s, int i)
    {
        servletResponse.setIntHeader(s, i);
    }

    @Override
    public void setLocale(Locale locale)
    {
        servletResponse.setLocale(locale);
    }

    @Override
    public void setStatus(int i)
    {
        servletResponse.setStatus(i);
    }

    @Override
    public void setStatus(int i, String s)
    {
        servletResponse.setStatus(i, s);
    }

    @Override
    public void sendError(int i)
        throws IOException
    {
        servletResponse.sendError(i);
    }

    @Override
    public void sendError(int i, String s)
        throws IOException
    {
        servletResponse.sendError(i, s);
    }

    @Override
    public void sendRedirect(String s)
        throws IOException
    {
        servletResponse.sendRedirect(s);
    }

    /**
     * Custom {@code ServletOutputStream} used to store the byte content of the
     * resulting file to serve.
     */
    private static class ByteArrayServletOutputStream
        extends ServletOutputStream
    {
        /**
         * Used to store the content of the file to serve before processing it
         * searching for server-side includes.
         */
        final ByteArrayOutputStream resContent = new ByteArrayOutputStream();

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
        {
        }
    }
}
