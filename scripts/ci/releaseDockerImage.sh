#!/usr/bin/env bash
set -x

scriptName=`basename "$0"`

usage="Usage: ${scriptName} [options]

    -h , --help           show this help text
    -v <releaseVersion>   the version number for the release (mandatory)"

while getopts ':hv:' option; do
  case "${option}" in
    h) echo -e "Build and publish the docker images for a release.\n\n${usage}"
       exit
       ;;
    v) releaseVersion=${OPTARG}
       ;;
    :) echo -e "Missing argument for -${OPTARG}\n\n${usage}" >&2
       exit 1
       ;;
   \?) echo -e "Illegal option: -${OPTARG}\n\n${usage}" >&2
       exit 1
       ;;
  esac
done
shift $((OPTIND - 1))

if [ "#${releaseVersion}" == "#" ]; then
    echo -e "Please supply a release version with the -v option.\n\n${usage}" >&2
    exit 1
fi

for dockerBuildDir in $(find . -name Dockerfile -exec dirname {} \;)
do
    if [[ "${dockerBuildDir}" != *"/classes/"* ]]
    then
        continue
    fi

    # The default resource copy also lands a Dockerfile at target/classes with no
    # build.properties beside it. That one is not an image we publish.
    if [[ ! -f "${dockerBuildDir}/build.properties" ]]
    then
        echo "No build.properties in ${dockerBuildDir}, not an image to publish"
        continue
    fi

    cd ${dockerBuildDir}
    source ./build.properties

    if [[ "${DOCKER_IMAGE_SKIP_PUSH}" == "true" ]]
    then
        echo "Skipping push for ${DOCKER_IMAGE_REPOSITORY}: DOCKER_IMAGE_SKIP_PUSH is true"
        cd -
        continue
    fi

    echo "Build and push docker image in:: ${dockerBuildDir}"
    docker buildx build --push --provenance=false \
        --label "GIT_COMMIT=$COMMIT_MESSAGE" \
        --label "GIT_BRANCH=$GITHUB_REF_NAME" \
        --tag "${DOCKER_IMAGE_REPOSITORY}:${releaseVersion}" \
        --platform linux/amd64,linux/arm64 .

    cd -
done
