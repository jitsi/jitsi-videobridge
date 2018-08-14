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
package org.jitsi.nlj.srtp;

import java.lang.reflect.*;
import java.security.*;
import java.util.*;

import org.bouncycastle.crypto.*;
import org.bouncycastle.crypto.engines.*;
import org.bouncycastle.crypto.params.*;

/**
 * Implements a factory for an AES <tt>BlockCipher</tt>.
 *
 * @author Lyubomir Marinov
 */
public class AES
{
    /**
     * The block size in bytes of the AES algorithm (implemented by the
     * <tt>BlockCipher</tt>s initialized by the <tt>AES</tt> class).
     */
    private static final int BLOCK_SIZE = 16;

    /**
     * The simple name of the <tt>BlockCipherFactory</tt> class/interface which
     * is used as a class name suffix by the well-known
     * <tt>BlockCipherFactory</tt> implementations.
     */
    private static final String BLOCK_CIPHER_FACTORY_SIMPLE_CLASS_NAME
        = "BlockCipherFactory";

    /**
     * The <tt>BlockCipherFactory</tt> implemented with BouncyCastle. It is the
     * well-known fallback.
     */
    private static final BlockCipherFactory BOUNCYCASTLE_FACTORY
        = new BouncyCastleBlockCipherFactory();

    /**
     * The <tt>BlockCipherFactory</tt> implementations known to the <tt>AES</tt>
     * class among which the fastest is to be elected as {@link #factory}.
     */
    private static BlockCipherFactory[] factories;

    /**
     * The <tt>BlockCipherFactory</tt> implementation which is (to be) used by
     * the class <tt>AES</tt> to initialize <tt>BlockCipher</tt>s.
     */
    private static BlockCipherFactory factory;

    /**
     * The name of the class to instantiate as a <tt>BlockCipherFactory</tt>
     * implementation to be used by the class <tt>AES</tt> to initialize
     * <tt>BlockCipher</tt>s.
     */
    private static final String FACTORY_CLASS_NAME = null;

    /**
     * The name of the <tt>ConfigurationService</tt> and/or <tt>System</tt>
     * property which specifies the name of the class to instantiate as a
     * <tt>BlockCipherFactory</tt> implementation to be used by the class
     * <tt>AES</tt> to initialize <tt>BlockCipher</tt>s.
     */
    private static final String FACTORY_CLASS_NAME_PNAME
        = AES.class.getName() + ".factoryClassName";

    /**
     * The <tt>Class</tt>es of the well-known <tt>BlockCipherFactory</tt>
     * implementations.
     */
    private static final Class<?>[] FACTORY_CLASSES
        = {
            BouncyCastleBlockCipherFactory.class,
            SunJCEBlockCipherFactory.class,
            SunPKCS11BlockCipherFactory.class,
        };

    /**
     * The number of milliseconds after which the benchmark which elected
     * {@link #factory} is to be considered expired.
     */
    public static final long FACTORY_TIMEOUT = 60 * 1000;

    /**
     * The class to instantiate as a <tt>BlockCipherFactory</tt> implementation
     * to be used to initialized <tt>BlockCipher</tt>s.
     *
     * @see #FACTORY_CLASS_NAME
     */
    private static Class<? extends BlockCipherFactory> factoryClass;

    /**
     * The time in milliseconds at which {@link #factories} were benchmarked and
     * {@link #factory} was elected.
     */
    private static long factoryTimestamp;

    /**
     * The input buffer to be used for the benchmarking of {@link #factories}.
     * It consists of blocks and its length specifies the number of blocks to
     * process for the purposes of the benchmark.
     */ 
    private static final byte[] in = new byte[BLOCK_SIZE * 1024];

    /**
     * The <tt>Logger</tt> used by the <tt>AES</tt> class to print out debug
     * information.
     */
//    private static final Logger logger = Logger.getLogger(AES.class);

    /**
     * The output buffer to be used for the benchmarking of {@link #factories}.
     */
    private static final byte[] out = new byte[BLOCK_SIZE];

    /**
     * The random number generator which generates keys and inputs for the
     * benchmarking of the <tt>BlockCipherFactory</tt> implementations.
     */
    private static final Random random = new Random();

    static
    {
//        ConfigurationService cfg = LibJitsi.getConfigurationService();
//
//        FACTORY_CLASS_NAME
//            = ConfigUtils.getString(cfg, FACTORY_CLASS_NAME_PNAME, null);
    }

    /**
     * Benchmarks a specific array/list of <tt>BlockCipherFactory</tt> instances
     * and returns the fastest-performing element.
     *
     * @param factories the <tt>BlockCipherFactory</tt> instances to benchmark
     * @param keySize AES key size (16, 24, 32 bytes)
     * @return the fastest-performing <tt>BlockCipherFactory</tt> among the
     * specified <tt>factories</tt>
     */
    private static BlockCipherFactory benchmark(
            BlockCipherFactory[] factories,
            int keySize)
    {
        Random random = AES.random;
        byte[] key = new byte[keySize];
        byte[] in = AES.in;

        random.nextBytes(key);
        random.nextBytes(in);

        CipherParameters params = new KeyParameter(key);
        int blockSize = BLOCK_SIZE;
        int inEnd = in.length - blockSize + 1;
        byte[] out = AES.out;
        long minTime = Long.MAX_VALUE;
        BlockCipherFactory minFactory = null;

        StringBuilder log = new StringBuilder();

        for (int f = 0; f < factories.length; ++f)
        {
            BlockCipherFactory factory = factories[f];

            if (factory == null)
                continue;

            try
            {
                BlockCipher cipher = factory.createBlockCipher(keySize);

                if (cipher == null)
                {
                    // The BlockCipherFactory failed to initialize a new
                    // BlockCipher instance. We will not use it again because
                    // the failure may persist.
                    factories[f] = null;
                }
                else
                {
                    cipher.init(true, params);

                    long startTime = System.nanoTime();

                    for (int inOff = 0;
                            inOff < inEnd;
                            inOff = inOff + blockSize)
                    {
                        cipher.processBlock(in, inOff, out, 0);
                    }
                    // We do not invoke the method BlockCipher.reset() so we do
                    // not need to take it into account in the benchmark.

                    long endTime = System.nanoTime();
                    long time = endTime - startTime;

                    if (time < minTime)
                    {
                        minTime = time;
                        minFactory = factory;
                    }

                    if (log.length() != 0)
                        log.append(", ");

                    log.append(getSimpleClassName(factory))
                        .append(' ')
                        .append(time);
                }
            }
            catch (Throwable t)
            {
                if (t instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                else if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }
        }

        if (log.length() != 0)
        {
            System.out.println("AES benchmark (of execution times expressed in nanoseconds: " + log);
        }

        return minFactory;
    }

    /**
     * Initializes a new <tt>BlockCipher</tt> instance which implements Advanced
     * Encryption Standard (AES).
     * @param keySize length of the AES key (16, 24, 32 bytes)
     *
     * @return a new <tt>BlockCipher</tt> instance which implements Advanced
     * Encryption Standard (AES)
     */
    public static BlockCipher createBlockCipher(int keySize)
    {
        BlockCipherFactory factory;

        synchronized (AES.class)
        {
            long now = System.currentTimeMillis();

            factory = AES.factory;
            if ((factory != null) && (now > factoryTimestamp + FACTORY_TIMEOUT))
                factory = null;
            if (factory == null)
            {
                try
                {
                    factory = getBlockCipherFactory(keySize);
                }
                catch (Throwable t)
                {
                    if (t instanceof InterruptedException)
                    {
                        Thread.currentThread().interrupt();
                    }
                    else if (t instanceof ThreadDeath)
                    {
                        throw (ThreadDeath) t;
                    }
                    else
                    {
                        System.out.println("AES#createBlockCipher failed to initialize an optimized AES implementation: " + t.getLocalizedMessage());
//                        logger.warn(
//                                "Failed to initialize an optimized AES"
//                                    + " implementation: "
//                                    + t.getLocalizedMessage());
                    }
                }
                finally
                {
                    if (factory == null)
                    {
                        factory = AES.factory;
                        if (factory == null)
                            factory = BOUNCYCASTLE_FACTORY;
                    }

                    AES.factoryTimestamp = now;
                    if (AES.factory != factory)
                    {
                        AES.factory = factory;
                        // Simplify the name of the BlockCipherFactory class to
                        // be employed for the purposes of brevity and ease.
                        System.out.println("AES#createBlockCipher will employ AES implemented by " + getSimpleClassName(factory));
//                        logger.info(
//                                "Will employ AES implemented by "
//                                    + getSimpleClassName(factory) + ".");
                    }
                }
            }
        }

        try
        {
            return factory.createBlockCipher(keySize);
        }
        catch (Exception ex)
        {
            if (ex instanceof RuntimeException)
            {
                throw (RuntimeException) ex;
            }
            else
            {
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Initializes the <tt>BlockCipherFactory</tt> instances to be benchmarked
     * by the class <tt>AES</tt> and among which the fastest-performing one is
     * to be selected.
     * 
     * @return the <tt>BlockCipherFactory</tt> instances to be benchmarked by
     * the class <tt>AES</tt> and among which the fastest-performing one is to
     * be selected
     */
    @SuppressWarnings("unchecked")
    private static BlockCipherFactory[] createBlockCipherFactories()
    {
        // The user may have specified a specific BlockCipherFactory class
        // (name) through the FACTORY_CLASS_NAME_PNAME ConfigurationService
        // and/or System property, Practically, the specified FACTORY_CLASS_NAME
        // will override all other FACTORY_CLASSES and, consequently, it does
        // not seem necessary to try FACTORY_CLASSES at all. Technically though,
        // the specified BlockCipherFactory may malfunction. That is why all
        // FACTORY_CLASSES are tried as well and FACTORY_CLASS_NAME is selected
        // later on after it has proven itself functional.
        Class<? extends BlockCipherFactory> factoryClass = AES.factoryClass;
        Class<?>[] factoryClasses = FACTORY_CLASSES;
        boolean add = true;

        if (factoryClass == null)
        {
            String factoryClassName = FACTORY_CLASS_NAME;

            if ((factoryClassName != null) && (factoryClassName.length() != 0))
            {
                // Support specifying FACTORY_CLASS_NAME without a package and
                // without BlockCipherFactory at the end for the purposes of
                // brevity and ease.
                if (Character.isUpperCase(factoryClassName.charAt(0))
                        && !factoryClassName.contains(".")
                        && !factoryClassName.endsWith(
                                BLOCK_CIPHER_FACTORY_SIMPLE_CLASS_NAME))
                {
                    factoryClassName
                        = AES.class.getName() + "$" + factoryClassName
                            + BLOCK_CIPHER_FACTORY_SIMPLE_CLASS_NAME;
                }

                // Is the specified FACTORY_CLASS_NAME one of the well-known
                // FACTORY_CLASSES? If it is, then we do not have to invoke the
                // method Class.forName(String) and add a new Class to
                // FACTORY_CLASSES.
                for (Class<?> clazz : factoryClasses)
                {
                    if ((clazz != null)
                            && clazz.getName().equals(factoryClassName)
                            && BlockCipherFactory.class.isAssignableFrom(clazz))
                    {
                        AES.factoryClass
                            = factoryClass
                                = (Class<? extends BlockCipherFactory>)
                                    clazz;
                        add = false;
                        break;
                    }
                }

                // If FACTORY_CLASS_NAME does not specify a well-known Class,
                // find and load the Class.
                if (add)
                {
                    try
                    {
                        Class<?> clazz = Class.forName(factoryClassName);
    
                        if (BlockCipherFactory.class.isAssignableFrom(clazz))
                        {
                            AES.factoryClass
                                = factoryClass
                                    = (Class<? extends BlockCipherFactory>)
                                        clazz;
                        }
                    }
                    catch (Throwable t)
                    {
                        if (t instanceof InterruptedException)
                        {
                            Thread.currentThread().interrupt();
                        }
                        else if (t instanceof ThreadDeath)
                        {
                            throw (ThreadDeath) t;
                        }
                        else
                        {
                            System.out.println("AES#createBlockCipherFactories failed to employ class " + factoryClassName +
                                    " as an AES implementation: " + t.getLocalizedMessage());
//                            logger.warn(
//                                    "Failed to employ class " + factoryClassName
//                                        + " as an AES implementation: "
//                                        + t.getLocalizedMessage());
                        }
                    }
                }
            }
        }

        // If FACTORY_CLASS_NAME does not specify a well-known Class, add the
        // new Class to FACTORY_CLASSES.
        if (add && (factoryClass != null))
        {
            for (Class<?> clazz : factoryClasses)
            {
                if (factoryClass.equals(clazz))
                {
                    add = false;
                    break;
                }
            }
            if (add)
            {
                Class<?>[] newFactoryClasses
                    = new Class<?>[1 + factoryClasses.length];

                newFactoryClasses[0] = factoryClass;
                System.arraycopy(
                        factoryClasses, 0,
                        newFactoryClasses, 1,
                        factoryClasses.length);
                factoryClasses = newFactoryClasses;
            }
        }

        return createBlockCipherFactories(factoryClasses);
    }

    /**
     * Initializes <tt>BlockCipherFactory</tt> instances of specific
     * <tt>Class</tt>es.
     *
     * @param classes the runtime <tt>Class</tt>es to instantiate
     * @return the <tt>BlockCipherFactory</tt> instances initialized by the
     * specified <tt>classes</tt>
     */
    private static BlockCipherFactory[] createBlockCipherFactories(
            Class<?>[] classes)
    {
        BlockCipherFactory[] factories = new BlockCipherFactory[classes.length];
        int i = 0;

        for (Class<?> clazz : classes)
        {
            try
            {
                if (BlockCipherFactory.class.isAssignableFrom(clazz))
                {
                    BlockCipherFactory factory;

                    if (BouncyCastleBlockCipherFactory.class.equals(clazz))
                        factory = BOUNCYCASTLE_FACTORY;
                    else
                        factory = (BlockCipherFactory) clazz.newInstance();

                    factories[i++] = factory;
                }
            }
            catch (Throwable t)
            {
                if (t instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                else if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }
        }
        return factories;
    }

    /**
     * Gets a <tt>BlockCipherFactory</tt> instance to be used by the
     * <tt>AES</tt> class to initialize <tt>BlockCipher</tt>s.
     *
     * <p>
     * Benchmarks the well-known <tt>BlockCipherFactory</tt> implementations and
     * returns the fastest one. 
     * </p>
     * @param keySize AES key size (16, 24, 32 bytes)
     *
     * @return a <tt>BlockCipherFactory</tt> instance to be used by the
     * <tt>AES</tt> class to initialize <tt>BlockCipher</tt>s
     */
    private static BlockCipherFactory getBlockCipherFactory(int keySize)
    {
        BlockCipherFactory[] factories = AES.factories;

        if (factories == null)
        {
            // A single instance of each well-known BlockCipherFactory
            // implementation will be initialized i.e. the attempt to initialize
            // BlockCipherFactory instances will be made once only.
            AES.factories = factories = createBlockCipherFactories();
        }

        // Benchmark the BlockCiphers provided by the available
        // BlockCipherFactories in order to select the fastest-performing
        // BlockCipherFactory.
        BlockCipherFactory minFactory = benchmark(factories, keySize);

        // The user may have specified a specific BlockCipherFactory class
        // (name) through the FACTORY_CLASS_NAME_PNAME ConfigurationService
        // and/or System property, Practically, FACTORY_CLASS_NAME may override
        // minFactory and, consequently, it may appear that the benchmark is
        // unnecessary. Technically though, the specified BlockCipherFactory may
        // malfunction. That is why FACTORY_CLASS_NAME is selected after it has
        // proven itself functional.
        {
            Class<? extends BlockCipherFactory> factoryClass = AES.factoryClass;

            if (factoryClass != null)
            {
                for (BlockCipherFactory factory : factories)
                {
                    if ((factory != null)
                            && factory.getClass().equals(factoryClass))
                    {
                        minFactory = factory;
                        break;
                    }
                }
            }
        }

        return minFactory;
    }

    /**
     * Gets the simple name of the runtime <tt>Class</tt> of a specific
     * <tt>BlockCipherFactory</tt> to be used for display purposes of brevity
     * and readability.
     *
     * @param factory the <tt>BlockCipherFactory</tt> for which a simple class
     * name is to be returned
     * @return the simple name of the runtime <tt>Class</tt> of the specified
     * <tt>factory</tt> to be used for display purposes of brevity and
     * readability
     */
    private static String getSimpleClassName(BlockCipherFactory factory)
    {
        Class<?> clazz = factory.getClass();
        String className = clazz.getSimpleName();

        if (className == null || className.length() == 0)
            className = clazz.getName();

        String suffix = BLOCK_CIPHER_FACTORY_SIMPLE_CLASS_NAME;

        if (className.endsWith(suffix))
        {
            String simpleClassName
                = className.substring(0, className.length() - suffix.length());
            String prefix = AES.class.getName() + "$";

            if (simpleClassName.startsWith(prefix))
            {
                className = simpleClassName.substring(prefix.length());
            }
            else if (simpleClassName.contains("."))
            {
                Package pkg = AES.class.getPackage();

                if (pkg != null)
                {
                    prefix = pkg.getName() + ".";
                    if (simpleClassName.startsWith(prefix))
                        className = simpleClassName.substring(prefix.length());
                }
            }
            else
            {
                className = simpleClassName;
            }
        }
        return className;
    }

    /**
     * Implements <tt>BlockCipherFactory</tt> using BouncyCastle.
     *
     * @author Lyubomir Marinov
     */
    public static class BouncyCastleBlockCipherFactory
        implements BlockCipherFactory
    {
        /**
         * {@inheritDoc}
         */
        public BlockCipher createBlockCipher(int keySize)
            throws Exception
        {
            // The value of keySize can be ignored for BouncyCastle, it
            // determines the AES algorithm to be used with the KeyParameter.
            return new AESFastEngine();
        }
    }

    /**
     * Implements <tt>BlockCipherFactory</tt> using Sun JCE.
     *
     * @author Lyubomir Marinov
     */
    public static class SunJCEBlockCipherFactory
        extends SecurityProviderBlockCipherFactory
    {
        /**
         * Initializes a new <tt>SunJCEBlockCipherFactory</tt> instance.
         */
        public SunJCEBlockCipherFactory()
        {
            super("AES_<size>/ECB/NoPadding", "SunJCE");
        }
    }

    /**
     * Implements <tt>BlockCipherFactory</tt> using Sun PKCS#11.
     *
     * @author Lyubomir Marinov
     */
    public static class SunPKCS11BlockCipherFactory
        extends SecurityProviderBlockCipherFactory
    {
        /**
         * The <tt>java.security.Provider</tt> instance (to be) employed for an
         * (optimized) AES implementation.
         */
        private static Provider provider;

        /**
         * The indicator which determines whether {@link #provider} is to be
         * used. If <tt>true</tt>, an attempt will be made to initialize a
         * <tt>java.security.Provider</tt> instance. If the attempt fails,
         * <tt>false</tt> will be assigned in order to not repeatedly attempt
         * the initialization which is known to have failed.
         */
        private static boolean useProvider = true;

        /**
         * Gets the <tt>java.security.Provider</tt> instance (to be) employed
         * for an (optimized) AES implementation.
         *
         * @return the <tt>java.security.Provider</tt> instance (to be) employed
         * for an (optimized) AES implementation
         */
        private static synchronized Provider getProvider()
            throws Exception
        {
            Provider provider = SunPKCS11BlockCipherFactory.provider;

            if ((provider == null) && useProvider)
            {
                try
                {
                    Class<?> clazz
                        = Class.forName("sun.security.pkcs11.SunPKCS11");

                    if (Provider.class.isAssignableFrom(clazz))
                    {
                        Constructor<?> contructor
                            = clazz.getConstructor(String.class);

                        // The SunPKCS11 Config name should be unique in order
                        // to avoid repeated initialization exceptions.
                        String name = null;
                        Package pkg = AES.class.getPackage();

                        if (pkg != null)
                            name = pkg.getName();
                        if (name == null || name.length() == 0)
                            name = "org.jitsi.impl.neomedia.transform.srtp";

                        provider
                            = (Provider)
                                contructor.newInstance(
                                        "--name=" + name + "\\n"
                                            + "nssDbMode=noDb\\n"
                                            + "attributes=compatibility");
                    }
                }
                finally
                {
                    if (provider == null)
                        useProvider = false;
                    else
                        SunPKCS11BlockCipherFactory.provider = provider;
                }
            }
            return provider;
        }

        /**
         * Initializes a new <tt>SunPKCS11BlockCipherFactory</tt> instance.
         *
         * @throws Exception if anything goes wrong while initializing a new
         * <tt>SunPKCS11BlockCipherFactory</tt> instance
         */
        public SunPKCS11BlockCipherFactory()
            throws Exception
        {
            super("AES_<size>/ECB/NoPadding", getProvider());
        }
    }
}
