#!/bin/bash

set -xeuo pipefail

export AFL_SKIP_CPUFREQ=1

# Test that jarfile instrumentation works without issues.
./java-afl-showmap -m 30000 -o out/tuples-forking.txt -- java -jar out/ins/test.jar < in/a.txt
tuples_forking=$(wc -l < out/tuples-forking.txt)
if [[ "$tuples_forking" -lt 6 ]]; then
    echo >&2 "Failed to generate enough tuples in forking implementation!"
    exit 1
fi

./java-afl-showmap -m 30000 -o out/tuples-forking.txt -- java -cp out/ins test.Forking < in/a.txt
tuples_forking=$(wc -l < out/tuples-forking.txt)
if [[ "$tuples_forking" -lt 6 ]]; then
    echo >&2 "Failed to generate enough tuples in forking implementation!"
    exit 1
fi

./java-afl-showmap -m 30000 -o out/tuples-deferred.txt -- java -cp out/ins test.Deferred < in/a.txt
tuples_deferred=$(wc -l < out/tuples-deferred.txt)
if [[ "$tuples_deferred" -lt 6 ]]; then
    echo >&2 "Failed to generate enough tuples in deferred implementation!"
    exit 1
fi

./java-afl-showmap -m 30000 -o out/tuples-persistent.txt -- java -cp out/ins test.Persistent < in/a.txt
tuples_persistent=$(wc -l < out/tuples-persistent.txt)
if [[ "$tuples_persistent" -lt 6 ]]; then
    echo >&2 "Failed to generate enough tuples in persistent implementation!"
    exit 1
fi
