#!/bin/sh

PLR=${PLR:-'0.1'}
DELAY=${DELAY:-100}
BW=${BW:-'10Mbit/s'}
PORT=10000

condition_egress() {
   # Create an anchor and reload pf conf.
   (cat /etc/pf.conf && echo "dummynet-anchor \"jitsi-videobridge\"" && echo "anchor \"jitsi-videobridge\"") | sudo pfctl -f -

   # Pipe all UDP traffic from port PORT through the egress pipe.
   echo "dummynet out quick proto udp from any port $PORT to any pipe 1" | sudo pfctl -a jitsi-videobridge -f -

   # Configure the egress pipe.
   sudo dnctl pipe 1 config bw $BW plr $PLR delay $DELAY
}

condition_ingress() {
   # Create an anchor and reload pf conf.
   (cat /etc/pf.conf && echo "dummynet-anchor \"jitsi-videobridge\"" && echo "anchor \"jitsi-videobridge\"") | sudo pfctl -f -

   # Pipe all UDP traffic to port PORT through the ingress pipe.
   echo "dummynet out quick proto udp from any to any port $PORT pipe 2" | sudo pfctl -a jitsi-videobridge -f -

   # Configure the ingress pipe.
   sudo dnctl pipe 2 config bw $BW plr $PLR delay $DELAY
}

condition_flush() {
   sudo dnctl -f flush
   sudo pfctl -f /etc/pf.conf
}

main() {
   case "$1" in
      ingress)
         condition_ingress
         ;;
      egress)
         condition_egress
         ;;
      flush)
         condition_flush
         ;;
   esac
}

main "$@"
