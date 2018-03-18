#!/bin/bash

set -xeuo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")"; pwd)
INSTALL_DIR=$DIR/installs

wget http://lcamtuf.coredump.cx/afl/releases/afl-2.52b.tgz
tar xf afl-2.52b.tgz
cd afl-2.52b
make -j "$(nproc || sysctl -n hw.ncpu)" PREFIX="$INSTALL_DIR"
make install PREFIX="$INSTALL_DIR"

export PATH=$INSTALL_DIR/bin:$PATH
cd "$DIR"

if [[ "$TRAVIS_OS_NAME" == linux ]]; then
    ./build.sh
    ./test.sh
    ./test-fuzz.sh
fi

# Test CMake builds:
( mkdir -p build-cmake && cd build-cmake && cmake .. -GNinja)
ninja -C build-cmake

# Test Bazel builds:
bazel build ...:all
