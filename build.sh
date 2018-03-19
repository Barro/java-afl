#!/bin/bash
#
# Copyright 2018  Jussi Judin
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -xeuo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")"; pwd)

ASM_URL=http://download.forge.ow2.org/asm/asm-6.1.jar
ASM_SHA256=db788a985a2359666aa29a9a638f03bb67254e4bd5f453a32717593de887b6b1
if [[ ! -f asm-6.1.jar ]]; then
    while true; do
        curl --retry 3 "$ASM_URL" > asm-6.1.jar.tmp
        calculated=$(sha256sum -b asm-6.1.jar.tmp | cut -f 1 -d " ")
        if [[ "$calculated" == "$ASM_SHA256" ]]; then
            mv asm-6.1.jar.tmp asm-6.1.jar
            break
        else
            echo >&2 "Checksum mismatch, $calculated != $ASM_SHA256. Re-downloading..."
        fi
    done
fi

if [[ ! -f asm-6.1.jar ]]; then
    echo -n >&2 "Could not find ASM 6.1. Please download asm-jar from "
    echo >&2 "https://forge.ow2.org/projects/asm/"
    exit 1
fi

mkdir -p out/ins out/test out/javafl
# TODO this is a crude script that enables testing everything by
# hand. Add more builder choices...
JNI_PATHS=(
    -Iout
    -I/usr/lib/jvm/java-8-openjdk-amd64/include
    -I/usr/lib/jvm/java-8-openjdk-amd64/include/linux)
CLASSPATH=asm-6.1.jar:out
javac -cp "$CLASSPATH" -d out javafl/CustomInit.java
javac -cp "$CLASSPATH" -d out javafl/JavaAfl.java
javac -cp "$CLASSPATH" -d out javafl/fuzz.java
javac -cp "$CLASSPATH" -d out javafl/JavaAflInstrument.java
javac -cp "$CLASSPATH" -d out javafl/run.java
javah -cp "$CLASSPATH" -d out -jni javafl.JavaAfl
cc -Os -shared -Wl,-soname,libjava-afl.so -o out/libjava-afl.so -fPIC "${JNI_PATHS[@]}" JavaAfl.c
javac -cp "$CLASSPATH" -d out javafl/JavaAflInject.java
java -cp "$CLASSPATH" javafl.JavaAflInject out/javafl/JavaAfl.class out/libjava-afl.so

(
    set -euo pipefail
    mkdir -p out/full/javafl
    cp out/javafl/JavaAfl.class out/full/javafl/
    cp out/javafl/JavaAfl\$*.class out/full/javafl/
    cp out/javafl/CustomInit.class out/full/javafl/
    cp out/javafl/fuzz.class out/full/javafl/
    cp out/javafl/run.class out/full/javafl/
    cp out/javafl/JavaAflInstrument.class out/full/javafl/
    cp out/javafl/JavaAflInstrument\$*.class out/full/javafl/
    cd out/full/
    jar xf "$DIR"/asm-6.1.jar
)
# Put everything that we need into one file:
jar -cfe "$DIR"/java-afl-instrument.jar javafl.JavaAflInstrument -C out/full .
# Same for the dynamic instrumentation:
jar -cfe "$DIR"/java-afl-run.jar javafl.run -C out/full .

# Test classes and jarfile
javac -d out/ test/Crashing.java
javac -d out/ test/Utils.java
javac -d out/ test/Forking.java
javac -d out/ test/Deferred.java
javac -d out/ test/Persistent.java
javac -d out/ test/Null.java
javac -d out/ test/NoAttribute.java
(
    set -euo pipefail
    cd out
    jar cfe test.jar test.Forking test/Utils.class test/Forking.class
)

java -jar java-afl-instrument.jar \
     out/ins \
     out/test.jar \
     out/test/Crashing.class \
     out/test/Utils.class \
     out/test/Forking.class \
     out/test/Deferred.class \
     out/test/Persistent.class \
     out/test/Null.class

java -jar java-afl-instrument.jar --custom-init \
     out/ins \
     out/test/NoAttribute.class
