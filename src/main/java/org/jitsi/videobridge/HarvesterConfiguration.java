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
package org.jitsi.videobridge;

import org.ice4j.*;
import org.ice4j.ice.harvest.*;

import org.jitsi.service.configuration.*;

import java.util.logging.*;

/**
 * HarvesterConfiguration is a single instance object holding the currently
 * configured mapping discovery, the found addresses and
 * the <tt>MappingCandidateHarvester</tt> harvester type we can later use.
 *
 * @author Damian Minkov
 */
public class HarvesterConfiguration
{
    /**
     * The <tt>Logger</tt> used by the <tt>HarvesterConfiguration</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(HarvesterConfiguration.class.getName());

    /**
     * Contains the name of the property that would tell us if we should use
     * address mapping as one of our NAT traversal options as well as the local
     * address that we should be mapping.
     */
    private static final String NAT_HARVESTER_LOCAL_ADDRESS
        = "org.jitsi.videobridge.NAT_HARVESTER_LOCAL_ADDRESS";

    /**
     * Contains the name of the property that would tell us if we should use
     * address mapping as one of our NAT traversal options as well as the public
     * address that we should be using in addition to our local one.
     */
    private static final String NAT_HARVESTER_PUBLIC_ADDRESS
        = "org.jitsi.videobridge.NAT_HARVESTER_PUBLIC_ADDRESS";

    /**
     * Contains the name of the property flag that may indicate that AWS address
     * harvesting should be explicitly disabled.
     */
    private static final String DISABLE_AWS_HARVESTER
        = "org.jitsi.videobridge.DISABLE_AWS_HARVESTER";

    /**
     * Contains the name of the property flag that may indicate that AWS address
     * harvesting should be forced without first trying to auto detect it.
     */
    private static final String FORCE_AWS_HARVESTER
        = "org.jitsi.videobridge.FORCE_AWS_HARVESTER";

    /**
     * String containing one or more stun server addresses in the form:
     * server_address:port, separated by commas.
     * Setting this property enables searching for our public address using
     * the specified stun servers.
     */
    private static final String STUN_MAPPING_HARVESTER_ADDRESSES
        = "org.jitsi.videobridge.STUN_MAPPING_HARVESTER_ADDRESSES";

    /**
     * Our local single instance
     */
    private static HarvesterConfiguration instance = null;

    /**
     * The addresses that we will use as a mask
     */
    private TransportAddress localAddress;

    /**
     * The addresses that we will be masking
     */
    private TransportAddress publicAddress;

    /**
     * The class of the <tt>MappingCandidateHarvester</tt>, so we can later
     * create new instances and return them when requested.
     */
    private Class<? extends MappingCandidateHarvester>
        candidateHarvesterClass = null;

    /**
     * Our private instance, on creation we check our configuration.
     * @param cfg
     */
    private HarvesterConfiguration(ConfigurationService cfg)
    {
        checkConfig(cfg);
    }

    /**
     * Returns our single instance.
     * @param cfg the <tt>ConfigurationService</tt> to use for checking config.
     * @return our single instance.
     */
    public static HarvesterConfiguration getInstance(ConfigurationService cfg)
    {
        synchronized (HarvesterConfiguration.class)
        {
            if (instance == null)
                instance = new HarvesterConfiguration(cfg);
        }

        return instance;
    }

    /**
     * Checks the config it can be only one. Currently implementions include:
     * - static address setting in the configuration
     * - AWS EC2 instance detection which is enabled by default and can be
     *   disabled or forced to be used even if no aws ec2 was discovered.
     * - generic discovering through setting list of stun servers to
     *   be contacted
     * @param cfg the <tt>ConfigurationService</tt> to use for checking config.
     */
    private void checkConfig(ConfigurationService cfg)
    {
        String localAddressStr
            = cfg.getString(NAT_HARVESTER_LOCAL_ADDRESS);
        String publicAddressStr
            = cfg.getString(NAT_HARVESTER_PUBLIC_ADDRESS);
        if (localAddressStr != null && publicAddressStr != null)
        {
            try
            {
                // port 9 is "discard", but the number here seems to be ignored.
                this.localAddress
                    = new TransportAddress(localAddressStr, 9, Transport.UDP);
                this.publicAddress
                    = new TransportAddress(publicAddressStr, 9, Transport.UDP);

                logger.info("Will append a NAT harvester for " +
                    localAddress + "=>" + publicAddress);
            }
            catch(Exception exc)
            {
                logger.info("Failed to create a NAT harvester for"
                    + " local address=" + localAddressStr
                    + " and public address=" + publicAddressStr);
                return;
            }

            // we are done with configuring
            return;
        }

        // we got here, lets check for aws
        AwsCandidateHarvester awsHarvester = null;
        //does this look like an Amazon AWS EC2 machine?
        if(AwsCandidateHarvester.smellsLikeAnEC2())
            awsHarvester = new AwsCandidateHarvester();

        //now that we have a conf service, check if AWS use is forced and
        //comply if necessary.
        if (awsHarvester == null
            && cfg.getBoolean(FORCE_AWS_HARVESTER, false))
        {
            //ok. this doesn't look like an EC2 machine but since you
            //insist ... we'll behave as if it is.
            logger.info("Forcing use of AWS candidate harvesting.");
            awsHarvester = new AwsCandidateHarvester();
        }

        //append the AWS harvester for AWS machines.
        if( awsHarvester != null
            && !cfg.getBoolean(DISABLE_AWS_HARVESTER, false))
        {
            logger.info("Appending an AWS harvester to the ICE agent.");
            candidateHarvesterClass = awsHarvester.getClass();

            this.localAddress = awsHarvester.getFace();
            this.publicAddress = awsHarvester.getMask();

            // we are done with configuring
            return;
        }

        // No settings for static mapping or aws detection, let's try stun conf
        String stunServers = cfg.getString(STUN_MAPPING_HARVESTER_ADDRESSES);

        if(stunServers != null)
        {
            StunMappingCandidateHarvester harv
                = new StunMappingCandidateHarvester(stunServers.split(","));

            logger.info("Appending a STUN harvester to the ICE agent.");
            candidateHarvesterClass = harv.getClass();

            this.localAddress = harv.getFace();
            this.publicAddress = harv.getMask();

            // we are done with configuring
            return;
        }
    }

    /**
     * The public address discovered.
     * @return the public address discovered.
     */
    public TransportAddress getPublicAddress()
    {
        return publicAddress;
    }

    /**
     * The local address discovered.
     * @return the local address discovered.
     */
    public TransportAddress getLocalAddress()
    {
        return localAddress;
    }

    /**
     * Returns a new instance of <tt>MappingCandidateHarvester</tt> to use
     * if available.
     * @return a new instance of <tt>MappingCandidateHarvester</tt> to use
     * if available.
     */
    public MappingCandidateHarvester getCandidateHarvester()
    {
        if(candidateHarvesterClass == null)
            return null;

        try
        {
            return candidateHarvesterClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException ex)
        {
            logger.log(Level.WARNING,
                "Cannot instantiate " + candidateHarvesterClass, ex);
            return null;
        }
    }
}
