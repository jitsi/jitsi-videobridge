/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.protocol.*;

import net.java.sip.communicator.impl.neomedia.control.*;

/**
 * Implements {@link PushBufferDataSource} for the purposes of {@link Content}
 * and {@link Channel} since Jitsi VideoBridge does not use a
 * <tt>CaptureDevice</tt> yet
 * <tt>RTPManager.createSendStream(DataSource, int)</tt> has to be called to
 * have <tt>RTPTranslatorImpl</tt> send packets.
 *
 * @author Lyubomir Marinov
 */
public class PushBufferDataSourceImpl
    extends PushBufferDataSource
{
    private final FormatControl formatControl
        = new AbstractFormatControl()
        {
            /**
             * The <tt>Format</tt> of this <tt>FormatControl</tt>.
             */
            private Format format;

            public Format[] getSupportedFormats()
            {
                return supportedFormats;
            }
        
            public Format getFormat()
            {
                if (format != null)
                    return format;

                Format[] supportedFormats = getSupportedFormats();

                return
                    ((supportedFormats == null)
                            || (supportedFormats.length == 0))
                        ? null
                        : supportedFormats[0];
            }

            @Override
            public Format setFormat(Format format)
            {
                /*
                 * PushBufferDataSourceImpl exists as a workaround to satisfy
                 * the requirements of the RTPTranslator implementation only and
                 * it never provides any media. It does not really matter what
                 * Format is being set.
                 */
                this.format = format;
                return this.format;
            }
        };

    private final PushBufferStream stream
        = new PushBufferStream()
        {
            public boolean endOfStream()
            {
                return false;
            }

            public ContentDescriptor getContentDescriptor()
            {
                return new ContentDescriptor(ContentDescriptor.RAW_RTP);
            }

            public long getContentLength()
            {
                return LENGTH_UNKNOWN;
            }

            public Object getControl(String controlType)
            {
                return AbstractControls.getControl(this, controlType);
            }

            public Object[] getControls()
            {
                return PushBufferDataSourceImpl.this.getControls();
            }

            public Format getFormat()
            {
                FormatControl formatControl
                    = (FormatControl) getControl(FormatControl.class.getName());

                return
                    (formatControl == null) ? null : formatControl.getFormat();
            }

            public void read(Buffer buffer)
                throws IOException
            {
            }

            public void setTransferHandler(
                    BufferTransferHandler transferHandler)
            {
            }
        };

    /**
     * The <tt>Format</tt>s in which this <tt>DataSource</tt> is capable of
     * providing media.
     */
    private final Format[] supportedFormats;

    public PushBufferDataSourceImpl(Format... supportedFormats)
    {
        this.supportedFormats = supportedFormats;
    }

    public void connect()
        throws IOException
    {
    }

    public void disconnect()
    {
    }

    public String getContentType()
    {
        return ContentDescriptor.RAW_RTP;
    }

    public Object getControl(String controlType)
    {
        return AbstractControls.getControl(this, controlType);
    }

    public Object[] getControls()
    {
        return new Object[] { formatControl };
    }

    public Time getDuration()
    {
        return DURATION_UNKNOWN;
    }

    public PushBufferStream[] getStreams()
    {
        return new PushBufferStream[] { stream };
    }

    public void start()
        throws IOException
    {
    }

    public void stop()
        throws IOException
    {
    }
}
