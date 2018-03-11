#!/bin/bash

set -xeuo pipefail

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
javac -d uninstrumented TestUtils.java
java -Djava.library.path=out/ JavaAflInstrument uninstrumented/TestUtils.class out/TestUtils.class
javac -d uninstrumented TestForking.java
java -Djava.library.path=out/ JavaAflInstrument uninstrumented/TestForking.class out/TestForking.class
# javac TestDeferred.java
# javac TestPersistent.java

# Test this:
AFL_SKIP_BIN_CHECK=1 afl-showmap -m 3000000 -o out/tuples-forking.txt -- java -Djava.library.path=out TestForking < in/a.txt
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
