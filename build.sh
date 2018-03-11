#!/bin/bash

set -xeuo pipefail

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
javac -d out TestNull.java
java -Djava.library.path=out/ JavaAflInstrument out/TestUtils.class out/TestForking.class out/TestNull.class
# javac TestDeferred.java
# javac TestPersistent.java

# Test this:
./java-afl-showmap -m 30000 -o out/tuples-forking.txt -- java -Djava.library.path=out TestForking < in/a.txt
tuples_forking=$(wc -l < out/tuples-forking.txt)
if [[ "$tuples_forking" -lt 6 ]]; then
    echo >&2 "Failed to generate enough tuples in forking implementation!"
    exit 1
fi
# AFL_SKIP_BIN_CHECK=1 afl-showmap -m 3000000 -o out/tuples-deferred.txt -- java -Djava.library.path=out TestDeferred < in/a.txt
# if [[ "$(wc -l out/tuples-deferred.txt)" -lt 6 ]]; then
#     echo >&2 "Failed to generate enough tuples in deferred implementation!"
#     exit 1
# fi

#AFL_SKIP_BIN_CHECK=1 afl-showmap -m 3000000 -o /dev/stderr -- java -Djava.library.path=out TestPersistent < in/a.txt
