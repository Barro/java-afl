#!/bin/bash

set -xeuo pipefail

BAZEL_VERSION=0.11.1

wget https://github.com/ninja-build/ninja/releases/download/v1.8.2/ninja-linux.zip -O "$TRAVIS_BUILD_DIR"/ninja-linux.zip
sudo unzip -d /usr/bin/ "$TRAVIS_BUILD_DIR"/ninja-linux.zip
wget https://github.com/bazelbuild/bazel/releases/download/"${BAZEL_VERSION}"/bazel_"${BAZEL_VERSION}"-linux-x86_64.deb
sudo dpkg -i bazel_"${BAZEL_VERSION}"-linux-x86_64.deb
