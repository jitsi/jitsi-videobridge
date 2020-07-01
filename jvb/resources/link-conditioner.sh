#!/bin/sh

PLR=${PLR:-'0.1'}
DELAY=${DELAY:-100}
BW=${BW:-'1Mbit/s'}
PORT=${PORT:-10000}
EGRESS_PIPE=1
INGRESS_PIPE=2

condition_egress() {
  # Create an anchor and reload pf conf.
  (cat /etc/pf.conf && echo "dummynet-anchor \"jitsi-videobridge\"" && echo "anchor \"jitsi-videobridge\"") | sudo pfctl -f -

  # Pipe all UDP traffic from port PORT through the EGRESS_PIPE.
  echo "dummynet out quick proto udp from any port $PORT to any pipe $EGRESS_PIPE" | sudo pfctl -a jitsi-videobridge -f -

  # Configure the egress pipe.
  sudo dnctl pipe $EGRESS_PIPE config bw $BW plr $PLR delay $DELAY
}

condition_ingress() {
  # Create an anchor and reload pf conf.
  (cat /etc/pf.conf && echo "dummynet-anchor \"jitsi-videobridge\"" && echo "anchor \"jitsi-videobridge\"") | sudo pfctl -f -

  # Pipe all UDP traffic to port PORT through the INGRESS_PIPE.
  echo "dummynet out quick proto udp from any to any port $PORT pipe $INGRESS_PIPE" | sudo pfctl -a jitsi-videobridge -f -

  # Configure the ingress pipe.
  sudo dnctl pipe $INGRESS_PIPE config bw $BW plr $PLR delay $DELAY
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
