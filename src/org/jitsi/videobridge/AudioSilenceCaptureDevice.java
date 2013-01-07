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
     * The compile-time flag which determines whether
     * <tt>AudioSilenceCaptureDevice</tt> and, more specifically,
     * <tt>AudioSilenceStream</tt> are to be used by <tt>AudioMixer</tt> for the
     * mere purposes of ticking the clock which makes <tt>AudioMixer</tt> read
     * media from its inputs, mix it, and write it to its outputs. The preferred
     * value is <tt>true</tt> because it causes the <tt>AudioMixer</tt> to not
     * push media unless at least one <tt>Channel</tt> is receiving actual
     * media.
     */
    private static final boolean CLOCK_ONLY = true;

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
        /**
         * The indicator which determines whether {@link #start()} has been
         * invoked on this instance without an intervening {@link #stop()}.
         */
        private boolean started;

        /**
         * The <tt>Thread</tt> which pushes available media data out of this
         * instance to its consumer i.e. <tt>BufferTransferHandler</tt>.
         */
        private Thread thread;

        /**
         * Initializes a new <tt>AudioSilenceStream</tt> which is to be exposed
         * by a specific <tt>AudioSilenceCaptureDevice</tt> and which is to have
         * its <tt>Format</tt>-related information abstracted by a specific
         * <tt>FormatControl</tt>.
         *
         * @param dataSource the <tt>AudioSilenceCaptureDevice</tt> which is
         * initializing the new instance and which is to expose it in its array
         * of <tt>PushBufferStream</tt>s
         * @param formatControl the <tt>FormatControl</tt> which is to abstract
         * the <tt>Format</tt>-related information of the new instance
         */
        public AudioSilenceStream(
                AudioSilenceCaptureDevice dataSource,
                FormatControl formatControl)
        {
            super(dataSource, formatControl);
        }

        /**
         * Reads available media data from this instance into a specific
         * <tt>Buffer</tt>.
         *
         * @param buffer the <tt>Buffer</tt> to write the available media data
         * into
         * @throws IOException if an I/O error has prevented the reading of
         * available media data from this instance into the specified
         * <tt>buffer</tt>
         */
        public void read(Buffer buffer)
            throws IOException
        {
            if (CLOCK_ONLY)
            {
                buffer.setLength(0);
            }
            else
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
        }

        /**
         * Runs in {@link #thread} and pushes available media data out of this
         * instance to its consumer i.e. <tt>BufferTransferHandler</tt>.
         */
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

        /**
         * Starts the transfer of media data from this instance.
         *
         * @throws IOException if an error has prevented the start of the
         * transfer of media from this instance
         */
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

        /**
         * Stops the transfer of media data from this instance.
         *
         * @throws IOException if an error has prevented the stopping of the
         * transfer of media from this instance
         */
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
