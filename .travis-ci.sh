#!/bin/bash

set -xeuo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")"; pwd)
INSTALL_DIR=$DIR/installs

wget http://lcamtuf.coredump.cx/afl/releases/afl-2.52b.tgz
tar xf afl-2.52b.tgz
cd afl-2.52b
make -j "$(nproc)" PREFIX="$INSTALL_DIR"
make install PREFIX="$INSTALL_DIR"

export PATH=$INSTALL_DIR/bin:$PATH
cd "$DIR"

./build.sh
./test.sh
./test-fuzz.sh
