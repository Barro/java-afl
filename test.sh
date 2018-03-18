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

# Default mode should produce differing files between runs:
java -jar java-afl-instrument.jar \
     out/default/1 \
     out/test/Utils.class
java -jar java-afl-instrument.jar \
     out/default/2 \
     out/test/Utils.class
if cmp --quiet out/default/1/test/Utils.class out/default/2/test/Utils.class; then
    echo >&2 "Instrumented files should be different by default!"
    exit 1
fi

# Deterministic mode should produce identical files between runs:
java -jar java-afl-instrument.jar \
     --deterministic \
     out/deterministic/1 \
     out/test/Utils.class
java -jar java-afl-instrument.jar \
     --deterministic \
     out/deterministic/2 \
     out/test/Utils.class
if ! cmp out/deterministic/1/test/Utils.class out/deterministic/2/test/Utils.class; then
    echo >&2 "Instrumented files should be identical in deterministic mode!"
    exit 1
fi

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

./java-afl-showmap -m 30000 -o /dev/null -- \
    java -cp out/ins test.NoAttribute < in/a.txt

rm -rf out/min/
./java-afl-cmin -m 30000 -i in/ -o out/min/ -- java -jar out/ins/test.jar
files_in=$(find in/ -type f | wc -l)
files_cmin=$(find out/min/ -type f | wc -l)
if [[ "$files_cmin" -eq "$files_in" ]]; then
    echo >&2 "java-afl-cmin does not seem to do its work!"
    echo >&2 "Files in in/ match the files in out/min/!"
    exit 1
fi

echo -n aaaaaa > out/to-min.txt
./java-afl-tmin -m 30000 -i out/to-min.txt -o out/min.txt -- java -jar out/ins/test.jar
size_orig=$(stat --format=%s out/to-min.txt)
size_min=$(stat --format=%s out/min.txt)
if [[ "$size_orig" -le "$size_min" ]]; then
    echo >&2 "java-afl-tmin does not seem to do its work!"
    echo >&2 "Input file was $size_orig bytes and output is $size_min bytes!"
    exit 1
fi
