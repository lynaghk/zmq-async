#!/bin/bash

set -e

#This script builds jzmq and pushes the native JAR

cd jzmq
git reset HEAD --hard


###############
#Build library

# Note, homebrew's pkg-config has issues; run:
#
#    eval `brew --config | grep HOMEBREW_PREFIX | sed 's/: /=/'`
#    sudo bash -c 'echo '$HOMEBREW_PREFIX/share/aclocal' >> `aclocal --print-ac-dir`/dirlist'
#
# to resolve.

./autogen.sh
./configure
make


###############
#Create jars

VERSION=`git rev-parse HEAD | cut -c 1-7`

mvn versions:set -DgenerateBackupPoms=false \
                 -DnewVersion=$VERSION

## It's not possible to change the group id using the maven versions plugin; use good ol' sed instead
sed -i "" "s/<groupId>org.zeromq/<groupId>com.keminglabs/" pom.xml

mvn package

JAR="jzmq-$VERSION.jar"
if [[ -f "target/$JAR" ]]; then
    mvn install:install-file -Dfile="target/$JAR" \
                             -DgroupId=com.keminglabs \
                             -Dversion=$VERSION \
                             -Dpackaging=jar \
                             -DartifactId=jzmq
    echo "lein deploy clojars com.keminglabs/jzmq $VERSION 'vendor/jzmq/target/$JAR' 'vendor/jzmq/pom.xml'"
fi

OSX_JAR="jzmq-$VERSION-native-x86_64-Mac OS X.jar"
if [[ -f "target/$OSX_JAR" ]]; then
    mvn install:install-file -Dfile="target/$OSX_JAR" \
                             -DgroupId=com.keminglabs \
                             -Dversion=$VERSION \
                             -Dpackaging=jar \
                             -DartifactId=jzmq-osx64
    mkdir -p osx64-pom
    cat ../poms/osx64/pom.xml | sed "s/VERSION/$VERSION/" > osx64-pom/pom.xml
    echo "lein deploy clojars com.keminglabs/jzmq-osx64 $VERSION 'vendor/jzmq/target/$OSX_JAR' 'vendor/jzmq/osx64-pom/pom.xml'"
fi


echo "Success! Please push generated JARs to Clojars by copy/pasting 'lein deploy' strings printed above."

