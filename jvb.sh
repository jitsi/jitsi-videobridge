HOST=localhost
DOMAIN=dev-two.packmeeting.net
PORT=5347
SECRET=barkley11
JVB_HOME=/home/khrim/pack-videobridge

mvn compile exec:java -Dexec.args="--host=$HOST --domain=$DOMAIN --port=$PORT --secret=$SECRET" -Djava.library.path=$JVB_HOME/lib/native/linux-64 -Djava.util.logging.config.file=$JVB_HOME/lib/logging.properties -Dnet.java.sip.communicator.SC_HOME_DIR_NAME=.pack-videobridge
