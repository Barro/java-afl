#!/bin/bash

set -xeuo pipefail

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

mkdir -p out
# TODO this is a crude script that enables testing everything by
# hand. Add more builder choices...
JNI_PATHS=(
    -Iout
    -I/usr/lib/jvm/java-8-openjdk-amd64/include
    -I/usr/lib/jvm/java-8-openjdk-amd64/include/linux)
export CLASSPATH=asm-6.1.jar:out
javac -d out JavaAfl.java
javac -d out JavaAflInstrument.java
javah -d out -jni JavaAfl
gcc -shared -Wl,-soname,libjava-afl.so -o out/libjava-afl.so -fPIC "${JNI_PATHS[@]}" JavaAfl.c

mkdir -p uninstrumented

# Test classes
javac -d out TestUtils.java
javac -d out TestForking.java
javac -d out TestDeferred.java
javac -d out TestPersistent.java
javac -d out TestNull.java
java -Djava.library.path=out/ JavaAflInstrument \
     out/TestUtils.class \
     out/TestForking.class \
     out/TestDeferred.class \
     out/TestPersistent.class \
     out/TestNull.class \
# javac TestDeferred.java

# Test this:
./java-afl-showmap -m 30000 -o out/tuples-forking.txt -- java -Djava.library.path=out TestForking < in/a.txt
tuples_forking=$(wc -l < out/tuples-forking.txt)
if [[ "$tuples_forking" -lt 6 ]]; then
    echo >&2 "Failed to generate enough tuples in forking implementation!"
    exit 1
fi

./java-afl-showmap -m 30000 -o out/tuples-deferred.txt -- java -Djava.library.path=out TestDeferred < in/a.txt
tuples_deferred=$(wc -l < out/tuples-deferred.txt)
if [[ "$tuples_deferred" -lt 6 ]]; then
    echo >&2 "Failed to generate enough tuples in deferred implementation!"
    exit 1
fi

./java-afl-showmap -m 30000 -o out/tuples-persistent.txt -- java -Djava.library.path=out TestPersistent < in/a.txt
tuples_persistent=$(wc -l < out/tuples-persistent.txt)
if [[ "$tuples_persistent" -lt 6 ]]; then
    echo >&2 "Failed to generate enough tuples in persistent implementation!"
    exit 1
fi
