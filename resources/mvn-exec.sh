#!/bin/sh -e

error_exit() {
  echo "$1" >&2
  exit 1
}

usage() {
  error_exit "Usage: $0 [-p JVB_PORT] [-r] [-f JVB_MVN_POM_FILE] [-d JVB_HOSTNAME] [-h JVB_HOST] [-s JVB_SECRET]"
}

JVB_LOGGING_CONFIG_FILE="${PREFIX}/etc/jitsi/videobridge/logging.properties"
JVB_CONFIG_FILE="${PREFIX}/etc/jitsi/videobridge/config"
JVB_HOME_DIR_NAME="videobridge"
JVB_HOME_DIR_LOCATION="${PREFIX}/etc/jitsi"
JVB_APIS="xmpp"
JVB_MVN_REPO_LOCAL="${PREFIX}/share/jitsi-videobridge/m2"
JVB_LOG_DIR_LOCATION="${PREFIX}/var/log/jitsi"
JVB_HOSTNAME=
JVB_HOST=
JVB_PORT=
JVB_SECRET=
JVB_EXTRA_JVM_PARAMS=
JVB_MVN_POM_FILE=
JVB_JAVA_PREFER_IPV4=false

# Source the JVB configuration file.
if [ -f "${JVB_CONFIG_FILE}" ]; then
  . "${JVB_CONFIG_FILE}"
fi

# Overide/complete with cmdline params.
while getopts ":d:h:s:f:p:r4" o; do
  case "${o}" in
    d)
      JVB_HOSTNAME="${OPTARG}"
      ;;
    p)
      JVB_PORT="${OPTARG}"
      ;;
    s)
      JVB_SECRET="${OPTARG}"
      ;;
    f)
      JVB_MVN_POM_FILE="${OPTARG}"
      ;;
    r)
      JVB_MVN_REBUILD=true
      ;;
    h)
      JVB_HOST="${OPTARG}"
      ;;
    4)
      JVB_JAVA_PREFER_IPV4=true
      ;;
    *)
      usage
      ;;
  esac
done

# Cmdline params validation and guessing.
if [ "${JVB_HOSTNAME}" = "" ]; then
  usage
fi

if [ "${JVB_SECRET}" = "" ]; then
  usage
fi

if [ "${JVB_PORT}" = "" ]; then
  # Guess the XMPP port to use.
  JVB_PORT=5347
fi

if [ ! -e "${JVB_MVN_POM_FILE}" ]; then
  # Guess the location of the pom file.
  JVB_MVN_POM_FILE="$(pwd)/jitsi-videobridge/pom.xml"
fi

if [ ! -e "${JVB_MVN_POM_FILE}" ]; then
  # Guess the location of the pom file.
  JVB_MVN_POM_FILE="$(pwd)/pom.xml"
fi

if [ ! -e "${JVB_MVN_POM_FILE}" ]; then
  error_exit "The maven pom file was not found."
fi

if [ ! -d "${JVB_LOG_DIR_LOCATION}" ] ; then
  mkdir "${JVB_LOG_DIR_LOCATION}"
fi

# Rebuild.
if [ ! ${JVB_MVN_REBUILD} = "" ]; then
  mvn -f "${JVB_MVN_POM_FILE}" clean compile -Dmaven.repo.local="${JVB_MVN_REPO_LOCAL}"
fi

# Execute.
exec mvn -f "${JVB_MVN_POM_FILE}" exec:exec -Dmaven.repo.local="${JVB_MVN_REPO_LOCAL}" -Dexec.executable=java -Dexec.args="-cp %classpath ${JVB_EXTRA_JVM_PARAMS} -Djava.util.logging.config.file=\"${JVB_LOGGING_CONFIG_FILE}\" -Dnet.java.sip.communicator.SC_HOME_DIR_NAME=\"${JVB_HOME_DIR_NAME}\" -Dnet.java.sip.communicator.SC_HOME_DIR_LOCATION=\"${JVB_HOME_DIR_LOCATION}\" -Dnet.java.sip.communicator.SC_LOG_DIR_LOCATION=\"${JVB_LOG_DIR_LOCATION}\" -Djna.nosys=true -Djava.net.preferIPv4Stack=\"${JVB_JAVA_PREFER_IPV4}\" org.jitsi.videobridge.Main --domain=\"${JVB_HOSTNAME}\" --host=\"${JVB_HOST}\" --port=\"${JVB_PORT}\" --secret=\"${JVB_SECRET}\" --apis=${JVB_APIS}"
