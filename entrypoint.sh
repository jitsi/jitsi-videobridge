#!/bin/bash -eux
if [ -z "$NAT_HARVESTER_PUBLIC_ADDRESS" ] && [ -z "$NAT_HARVESTER_LOCAL_ADDRESS" ]; then
  echo "org.ice4j.ice.harvest.NAT_HARVESTER_PUBLIC_ADDRESS=${NAT_HARVESTER_PUBLIC_ADDRESS}" > /root/.sip-communicator/sip-communicator.properties
  echo "org.ice4j.ice.harvest.NAT_HARVESTER_LOCAL_ADDRESS=${NAT_HARVESTER_LOCAL_ADDRESS}" >> /root/.sip-communicator/sip-communicator.properties
fi
/jvb/jvb.sh --host=${XMPP_HOST} --domain=${DOMAIN} --port=5347 --secret=${SECRET}
