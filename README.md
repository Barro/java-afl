This is a fork server based approach to fuzz Java applications on Java
virtual machine with [american fuzzy lop](http://lcamtuf.coredump.cx/afl/).

Tested with:

* afl 2.52b
* OpenJDK 1.8.0_151

Speed:

* Fork server mode around 600 executions/second for a program that
  does nothing. Closer to 300 when there is actually something
  happening.
* Persistent mode around 10000 executions/second. Highly depends on
  how much JVM is able to optimize.

## Usage

TODO description and easy to use implementation.

Instrument:

```
$ export CLASSPATH=asm-6.1.jar:out/
$ java -Djava.library.path=out/ JavaAflInstrument out/ClassToInstrument.class
```

Fuzz. JVM allocates on my Debian system around 10 gigabytes of virtual
memory. You might find [recidivm](http://jwilk.net/software/recidivm)
useful in estimating memory limits:

```
$ java-afl-fuzz -m 20000 -i in/ -o out/ -- java -Djava.library.path=out/ ClassToInstrument
```

## Building

You need to have [ASM 6.1](http://asm.ow2.org/) to build this as a
dependency in addition to Java 8 and afl build dependencies. Currently
there is a crude build script to build and test this implementation:

```
$ ./build.sh
```

TODO description and easy to use implementation.

## Greetz

* Great thanks to [Michał Zalewski](http://lcamtuf.coredump.cx/) for
  [american fuzzy lop](http://lcamtuf.coredump.cx/afl), a crude but
  effective fuzzer. Especially the idea of using a bitmap and randomly
  generated program locations as a fast probabilistic memory bound
  approximation of the program execution path.
* Inspired by [python-afl](http://jwilk.net/software/python-afl) and
  [Kelinci](https://github.com/isstac/kelinci).

## License

Copyright 2018  Jussi Judin

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
