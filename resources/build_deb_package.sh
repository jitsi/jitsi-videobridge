#!/bin/bash
set -x
set -e

# Make sure you have the following environment variables set, example:
# export DEBFULLNAME="Jitsi Team"
# export DEBEMAIL="dev@jitsi.org"
# You need package devscripts installed (command dch) and libxml2-utils(provides xmllint), dh-systemd.
#
# There is an optional param that can be used to add an extra string to the version.
# That string can be used to mark custom version passing "hf" as param
# will result debian version 2.1-123-gabcabcab-hf-1, instead of just 2.1-123-gabcabcab-1
VER_EXTRA_LABEL=$1

echo "==================================================================="
echo "   Building DEB packages...   "
echo "==================================================================="

SCRIPT_FOLDER=$(dirname "$0")
cd "$SCRIPT_FOLDER/.."

# Let's get version from maven
MVNVER=$(xmllint --xpath "/*[local-name()='project']/*[local-name()='version']/text()" pom.xml)
TAG_NAME="v${MVNVER/-SNAPSHOT/}"

echo "Current tag name: $TAG_NAME"

if ! git rev-parse "$TAG_NAME" >/dev/null 2>&1
then
  git tag -a "$TAG_NAME" -m "Tagged automatically by Jenkins"
  git push origin "$TAG_NAME"
else
  echo "Tag: $TAG_NAME already exists."
fi

VERSION_FULL=$(git describe --match "v[0-9\.]*" --long)
echo "Full version: ${VERSION_FULL}"

export VERSION=${VERSION_FULL:1}
if [ -n "${VER_EXTRA_LABEL}" ]
then
  VERSION+="-${VER_EXTRA_LABEL}"
fi
echo "Package version: ${VERSION}"

REV=$(git log --pretty=format:'%h' -n 1)
dch -v "$VERSION-1" "Build from git. $REV"
dch -D unstable -r ""

# sets the version in the pom file so it will propagate to resulting jar
mvn versions:set -DnewVersion="${VERSION}"

# We need to make sure all dependencies are downloaded before start building
# the debian package
mvn package

# now build the deb
dpkg-buildpackage -tc -us -uc -A

# clean the current changes as dch had changed the change log
git checkout debian/changelog
git checkout pom.xml

echo "Here are the resulting files in $(pwd ..)"
echo "-----"
ls -l ../{*.changes,*.deb,*.buildinfo}
echo "-----"

# Let's try deploying
cd ..
([ ! -x deploy.sh ] || ./deploy.sh "jvb" $VERSION )
