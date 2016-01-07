#!/bin/bash
PUSHCHANGES="false"
while [[ $# > 1 ]]
do
key="$1"

case $key in
    -r|--releaseVersion)
    RELEASEVERSION="$2"
    shift # past argument
    ;;
    -d|--developmentVersion)
    DEVELOPMENTVERSION="$2"
    shift # past argument
    ;;
    -p|--pushChanges)
    PUSHCHANGES="true"
    ;;
    *)
    # unknown option
    ;;
esac
shift # past argument or value
done

mvn -P release -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:0.23.0:set-version -DnewVersion=$RELEASEVERSION

pushd libraries
git commit -a -m "prepare release $RELEASEVERSION"
git tag v$RELEASEVERSION
popd
git commit -a -m "prepare release $RELEASEVERSION"
git tag v$RELEASEVERSION

mvn -P release -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:0.23.0:set-version -DnewVersion=$DEVELOPMENTVERSION

pushd libraries
git commit -a -m "prepare for next development iteration"
popd
git commit -a -m "prepare for next development iteration"
