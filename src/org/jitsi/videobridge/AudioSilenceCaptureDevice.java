/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.util.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.format.*;
import javax.media.protocol.*;

import net.sf.fmj.media.util.*;

import org.jitsi.impl.neomedia.jmfext.media.protocol.*;

/**
 * Implements a <tt>CaptureDevice</tt> which provides silence in the form of
 * audio media.
 *
 * @author Lyubomir Marinov
 */
public class AudioSilenceCaptureDevice
    extends AbstractPushBufferCaptureDevice
{
    /**
     * The list of <tt>Format</tt>s supported by the
     * <tt>AudioSilenceCaptureDevice</tt> instances.
     */
    private static final Format[] SUPPORTED_FORMATS
        = new Format[]
                {
                    new AudioFormat(
                            AudioFormat.LINEAR,
                            48000,
                            16,
                            1,
                            AudioFormat.LITTLE_ENDIAN,
                            AudioFormat.SIGNED,
                            Format.NOT_SPECIFIED,
                            Format.NOT_SPECIFIED,
                            Format.byteArray)
                };

    /**
     * {@inheritDoc}
     *
     * Implements
     * {@link AbstractPushBufferCaptureDevice#createStream(int, FormatControl)}.
     */
    protected AbstractPushBufferStream createStream(
            int streamIndex,
            FormatControl formatControl)
    {
        return new AudioSilenceStream(this, formatControl);
    }

    /**
     * {@inheritDoc}
     *
     * Overrides the super implementation in order to return the list of
     * <tt>Format</tt>s hardcoded as supported in
     * <tt>AudioSilenceCaptureDevice</tt> because the super looks them up by
     * <tt>CaptureDeviceInfo</tt> and this instance does not have one.
     */
    @Override
    protected Format[] getSupportedFormats(int streamIndex)
    {
        return SUPPORTED_FORMATS.clone();
    }

    /**
     * Implements a <tt>PushBufferStream</tt> which provides silence in the form
     * of audio media.
     */
    private static class AudioSilenceStream
        extends AbstractPushBufferStream
        implements Runnable
    {
        private boolean started;

        private Thread thread;

        public AudioSilenceStream(
                AudioSilenceCaptureDevice dataSource,
                FormatControl formatControl)
        {
            super(dataSource, formatControl);
        }

        public void read(Buffer buffer)
            throws IOException
        {
            AudioFormat format = (AudioFormat) getFormat();
            int frameSizeInBytes
                = format.getChannels()
                    * (((int) format.getSampleRate()) / 50)
                    * (format.getSampleSizeInBits() / 8);

            Object data = buffer.getData();
            byte[] bytes;

            if (data instanceof byte[])
            {
                bytes = (byte[]) data;
                if (bytes.length <= frameSizeInBytes)
                {
                    bytes = new byte[frameSizeInBytes];
                    buffer.setData(bytes);
                }
            }
            else
            {
                bytes = new byte[frameSizeInBytes];
                buffer.setData(bytes);
            }

            Arrays.fill(bytes, 0, frameSizeInBytes, (byte) 0);

            buffer.setFormat(format);
            buffer.setLength(frameSizeInBytes);
            buffer.setOffset(0);
        }

        public void run()
        {
            try
            {
                Thread.currentThread().setPriority(
                        MediaThread.getAudioPriority());

                while (true)
                {
                    try
                    {
                        Thread.sleep(20);
                    }
                    catch (InterruptedException ie)
                    {
                    }

                    synchronized (this)
                    {
                        /*
                         * If the current Thread is no longer utilized by this
                         * AudioSilenceStream, it no longer has the right to
                         * touch it. If this AudioSilenceStream has been
                         * stopped, the current Thread should stop as well. 
                         */
                        if ((thread != Thread.currentThread()) || !started)
                            break;
                    }

                    BufferTransferHandler transferHandler
                        = this.transferHandler;

                    if (transferHandler != null)
                    {
                        try
                        {
                            transferHandler.transferData(this);
                        }
                        catch (Throwable t)
                        {
                            if (t instanceof ThreadDeath)
                                throw (ThreadDeath) t;
                            else
                            {
                                // TODO Auto-generated method stub
                            }
                        }
                    }
                }
            }
            finally
            {
                synchronized (this)
                {
                    if (thread == Thread.currentThread())
                    {
                        thread = null;
                        started = false;
                        notifyAll();
                    }
                }
            }
        }

        @Override
        public synchronized void start()
            throws IOException
        {
            if (thread == null)
            {
                thread = new Thread(this, getClass().getName());
                thread.setDaemon(true);

                boolean started = false;

                try
                {
                    thread.start();
                    started = true;
                }
                finally
                {
                    this.started = started;

                    if (!started)
                    {
                        thread = null;
                        notifyAll();

                        throw new IOException(
                                "Failed to start " + getClass().getName());
                    }
                }
            }
        }

        @Override
        public synchronized void stop()
            throws IOException
        {
            this.started = false;
            notifyAll();

            boolean interrupted = false;

            while (thread != null)
            {
                try
                {
                    wait();
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }
}
