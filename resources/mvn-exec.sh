#!/bin/sh -e

function error_exit
{
  echo "$1" >&2
  exit 1
}

function usage
{
  error_exit "Usage: $0 [-p XMPP_PORT] [-r] -f MVN_POM_FILE -d XMPP_DOMAIN -s XMPP_SECRET"
}

# Get cmdline params.
while getopts ":d:s:f:p:r4" o; do
  case "${o}" in
    d)
      XMPP_DOMAIN="${OPTARG}"
      ;;
    p)
      XMPP_PORT="${OPTARG}"
      ;;

    s)
      XMPP_SECRET="${OPTARG}"
      ;;
    f)
      MVN_POM_FILE="${OPTARG}"
      ;;
    r)
      MVN_REBUILD=true
      ;;
    4)
      JAVA_PREFER_IPV4=true
      ;;
    *)
      usage
      ;;
  esac
done

# Cmdline params validation.
if [ "${XMPP_DOMAIN}" = "" ]; then
  usage
fi

if [ "${XMPP_SECRET}" = "" ]; then
  usage
fi

if [ "${XMPP_PORT}" = "" ]; then
  XMPP_PORT=5347
fi

if [ ! -e "${MVN_POM_FILE}" ]; then
  error_exit "The maven pom file was not found."
fi

echo Running with:
echo
echo XMPP_DOMAIN="${XMPP_DOMAIN}"
echo XMPP_SECRET="${XMPP_SECRET}"
echo XMPP_PORT="${XMPP_PORT}"
echo MVN_POM_FILE="${MVN_POM_FILE}"
echo MVN_REBUILD="${MVN_REBUILD}"
echo JAVA_PREFER_IPV4="${JAVA_PREFER_IPV4}"

ARTIFACT_ID=jitsi-videobridge

# Setup variables based on the artifactId.
SC_HOME_DIR_NAME=".${ARTIFACT_ID}"
SC_HOME_DIR_LOCATION="${HOME}"
SC_HOME_DIR_ABSOLUTE_PATH="${SC_HOME_DIR_LOCATION}/${SC_HOME_DIR_NAME}"
MVN_REPO_LOCAL="${SC_HOME_DIR_ABSOLUTE_PATH}/m2"
LOG_LOCATION="${SC_HOME_DIR_ABSOLUTE_PATH}/log"
ARCHIVE_LOCATION="${SC_HOME_DIR_ABSOLUTE_PATH}/archive"

# Setup variables based on the source code location.
SRC_LOCATION="$(basename ${MVN_POM_FILE})"
LOGGING_CONFIG_FILE="${SRC_LOCATION}/lib/logging.properties"

if [ ! -d "${ARCHIVE_LOCATION}" ] ; then
  mkdir -p "${ARCHIVE_LOCATION}"
fi

if [ -d "${LOG_LOCATION}" ] ; then
  ARCHIVE_NAME="$(date '+%Y-%m-%d-%H-%M-%S')"
  mv "${LOG_LOCATION}" "${ARCHIVE_LOCATION}/${ARCHIVE_NAME}"
  tar jcvf "${ARCHIVE_LOCATION}/${ARCHIVE_NAME}.tar.bz2" "${ARCHIVE_LOCATION}/${ARCHIVE_NAME}"
  rm -rf "${ARCHIVE_LOCATION}/${ARCHIVE_NAME}"
fi

if [ ! -d "${LOG_LOCATION}" ] ; then
  mkdir "${LOG_LOCATION}"
fi

# Maybe clean.
if ${MVN_REBUILD} ; then
  mvn -f "${MVN_POM_FILE}" clean compile -Dmaven.repo.local="${MVN_REPO_LOCAL}"
fi

exec mvn -f "${MVN_POM_FILE}" exec:java \
  -Djna.nosys=true \
  -Dexec.args="--domain=${XMPP_DOMAIN} --host=${XMPP_HOST} --port=${XMPP_PORT} --secret=${XMPP_SECRET} --apis=xmpp,rest" \
  -Djava.net.preferIPv4Stack=true \
  -Djava.util.logging.config.file="${LOGGING_CONFIG_FILE}" \
  -Dnet.java.sip.communicator.SC_HOME_DIR_NAME="${SC_HOME_DIR_NAME}" \
  -Dnet.java.sip.communicator.SC_HOME_DIR_LOCATION="${SC_HOME_DIR_LOCATION}" \
  -Dmaven.repo.local="${MVN_REPO_LOCAL}" 2>&1 | tee "${LOG_LOCATION}/jvb.log"

