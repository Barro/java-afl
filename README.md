This is a fork server based approach to fuzz Java applications on Java
virtual machine with
[american fuzzy lop](http://lcamtuf.coredump.cx/afl/).

## Usage

Fuzzing with american fuzzy lop works by instrumenting the compiled
Java bytecode with probabilistic program coverage revealing
instrumentation. There are general types of instrumeting fuzzing modes
in programs that can be fuzzed with `afl-fuzz` command. The default
fork server mode does not need any modifications to the program source
code and can work as is. There are also more efficient deferred fork
server and persistent modes that enable you to skip some
initialization code and keep the JVM running longer than for just one
input.

### Ahead of time instrumentation

Ahead of time instrumentation works by instrumenting specific .jar or
.class files that you want to run with `afl-fuzz` for your
program. This is done by running the built `java-afl-instrument.jar`
and instrumenting each jar or class file that you want to include in
your program. No source code modifications are necessary to get
started:

```bash
$ java -jar java-afl-instrument.jar instrumented/ ClassToTest.class
$ java -jar java-afl-instrument.jar instrumented/ jar-to-test.jar
```

As instrumentation injects native JNI code into the used files, so you
can only run these files on similar enough systems that
`java-afl-instrument.jar` was run on.

Then you are ready to fuzz your Java application with `afl-fuzz`. It
can be done with this type of command with the provided
`java-afl-fuzz` wrapper script:

```bash
$ java-afl-fuzz -m 20000 -i in/ -o /dev/shm/fuzz-out/ -- java -cp instrumented/ ClassToTest
$ java-afl-fuzz -m 20000 -i in/ -o /dev/shm/fuzz-out/ -- java -jar instrumented/jar-to-test.jar
```

### Just in time instrumentation

Just in time instrumentation works by wrapping the main function of a
program that you want to run around a custom instrumentation injecting
[ClassLoader](https://docs.oracle.com/javase/7/docs/api/java/lang/ClassLoader.html). This
way you will get more thorough instrumentation than just running ahead
of time instrumentation on your program, but at the same time the
instrumentation likely covers code that you are not interested in.

Just in time instrumentation works by adding both `java-afl-run.jar`
and the target classes to CLASSPATH and running `javafl.run`
class with the target class name as a parameter:

```bash
$ java-afl-fuzz -m 20000 -i in/ -o /dev/shm/fuzz-out/ \
      -- java -cp java-afl-run.jar:. javafl.run ClassToTest
$ java-afl-fuzz -m 20000 -i in/ -o /dev/shm/fuzz-out/ \
      -- java -cp java-afl-run.jar:jar-to-test.jar javafl.run ClassToTest
```

Notice that there is no need to first instrument the class files, as
it is done on fly. This has same platform specific limitations as
ahead of time compilation, as this instrumentation injects native JNI
code into the used files. So you can only fuzz programs with
`java-afl-run.jar` on similar enough systems that `java-afl-run.jar`
was built on.

### java-afl-fuzz parameters

Parameters to `java-afl-fuzz` command have following functions:

* `-i in/`: Input directory of initial data that then gets modified
  over the fuzzing process.
* `-o /dev/shm/fuzz-out/`: Output directory for fuzzing state
  data. This should always be on a shared memory drive and never in a
  directory pointing to a physical hard drive.
* `-m 20000`: Higher virtual memory limit that enables JVM to run, as
  the default memory limit in `afl-fuzz` is 50 megabytes. JVM can
  allocate around 10 gigabytes of virtual memory by default.

More detailed description of available options can be found from
[american fuzzy lop's README](http://lcamtuf.coredump.cx/afl/README.txt). You
may also want to adjust maximum heap size with
[`-Xmx`](https://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm#PERFM164)
option to be smaller than the default if you fuzz multiple JVM
instances on the same machine to keep memory usage sane.

### Advanced usage

More efficient deferred and persistent modes start each fuzzing
iteration later than at the beginning of `main()` function. Using
deferred or persistent mode requires either a special annotation for
the `main()` function or `--custom-init` flag to the instrument
program:


```java
public class ProgramCustom {
    @javafl.CustomInit
    public static void main(String args[]) {
        ...
    }
}
```

Or you can instrument unmodified code in such way that the init
function does not need to reside inside `main()` by making
`--custom-init` as the first parameter:

```bash
$ java -jar java-afl-instrument.jar --custom-init instrumented/ ClassToTest.class
$ java -jar java-afl-instrument.jar --custom-init instrumented/ jar-to-test.jar
```

To put the application into deferred mode where all the initialization
code that comes before `javafl.fuzz.init()` function can be done in
following fashion:

```java
public class ProgramPersistent {
    @javafl.CustomInit
    public static void main(String[] args) {
        ...
        javafl.fuzz.init();
        // You need to read the actual input after initialization point.
        System.in.read(data_buffer);
        ... do actual input processing...
    }
}
```

To put the program into a persistent mode you need wrap the part that
you want to execute around a `while (javafl.fuzz.loop(<iterations>))`
loop. If you read the input from `System.in`, you need to take care
that you flush Java's buffering on it after you have read your data:

```java
public class ProgramPersistent {
    @javafl.CustomInit
    public static void main(String[] args) {
        ...
        byte[] data = new byte[128];
        int read = 128;
        while (javafl.fuzz.loop(100000)) {
            read = System.in.read(data, 0, data.length);
            // Throw away all buffering information from stdin for the
            // next iteration:
            System.in.skip(9999999);
            ... do actual input processing...
        }
        ...
    }
}
```

### Options controlling instrumentation

Command line switches to `java-afl-instrument.jar`:

* `--custom-init`
* `--deterministic`: by default java-afl produces random class files
  to make it possible to probabilistically get bigger coverage on the
  program from two differently instrumented programs than from
  one. This switch makes the instrumentation depend solely on the
  input data for each class and will always result in the same result
  between different instrumentation runs.

Environmental variables:

* `AFL_INST_RATIO`: by default 100% of program control flow altering
  locations are instrumented. This makes it possible to
  probabilistically select a smaller instrumentation ratio. Smaller
  instrumentation ratios are useful in big programs where resulting
  program execution path traces would otherwise fill the default 16
  bit state map and increasing the map size would add unneeded
  performance penalty.

## Building

As there are tons of different tools to build Java programs with
automatic dependency fetching, java-afl supports more than one way to
build itself.

If you pass american fuzzy lop's source code directory that has
`config.h` file in it, you can pass following C flags to JNI
compilation part:

```
CFLAGS="-I<path-to-afl-src-dir> -DHAVE_AFL_CONFIG_H"
```

This makes the compiled information match to what afl-fuzz expects if
it has been modified in any way. Build systems also try to deduce this
(TODO) during compilation from existing `afl-showmap` command if such
exists.

### Bazel

[Bazel](https://bazel.build/) a build tool that can handle very large
programs with ease.

```bash
$ bazel build :java-afl-instrument_deploy.jar
$ bazel-bin/java-afl-instrument files-to-instrument...
```

### CMake

[CMake](https://cmake.org/) is the PHP of build systems. Widely
available and gets stuff done but becomes quite painful after a
while.

```bash
$ ( mkdir -p build-cmake && cd build-cmake && cmake .. -GNinja )
$ ninja -C build-cmake
$ java -jar build-cmake/java-afl-instrument.jar files-to-instrument...
```

### Travis CI [![Build Status](https://travis-ci.org/Barro/java-afl.svg?branch=master)](https://travis-ci.org/Barro/java-afl)

Requires Ubuntu 14.04 based system. You need to have
[ASM 6.1](http://asm.ow2.org/) to build this as a dependency in
addition to Java 8 and afl build dependencies. Currently there is a
crude build script to build and test this implementation:

```bash
$ ./build.sh
```

Even though building requires Java 8, this should be able to
instrument programs that run only on some older versions of Java.

## Performance

Performance numbers on Intel Core i7-3770K CPU @ 3.50GHz with OpenJDK
1.8.0_151 and afl 2.52b:

* Fork server mode around 750 executions/second for a program that
  does nothing. Closer to 300 when there is actually something
  happening.
* Deferred mode naturally gets something between the fork server mode
  and persistent mode. Depends how heavy the initialization is,
  probably maybe some tens of percents.
* Persistent mode around 14000 executions/second. Highly depends on
  how much and how long JVM is able to optimize before being
  killed. Around 31000 iterations/second for an empty while loop, that
  is close to the maximum that native C code can handle with `afl-fuzz`
  in persistent mode.

## TODO

* Support instrumentation generation at runtime with an alternative
  class-loader.
* Support deferred init for arbitrary given method without source code
  modifications.
* Dynamically figure out the used map size from afl-showmap.
* Better way to build this. Multiple different build tools are
  probably a must.
  * Ant
  * Maven
  * Gradle
* Alternative method implementations based on fuzzing mode (similar to
  C preprocessor's #ifdef/#ifndef). Probably somehow with annotations
  or `System.getProperty("FUZZING_BUILD_MODE_UNSAFE_FOR_PRODUCTION")`.

## Greetz

* Great thanks to [Michał Zalewski](http://lcamtuf.coredump.cx/) for
  [american fuzzy lop](http://lcamtuf.coredump.cx/afl), a crude but
  effective fuzzer. Especially the idea of using a bitmap and randomly
  generated program locations as a fast probabilistic memory bound
  approximation of the program execution path.
* Inspired by [python-afl](http://jwilk.net/software/python-afl) and
  [Kelinci](https://github.com/isstac/kelinci).

## Dependencies

Mandatory dependencies to build this:

* GNU/Linux system with recently new basic utilities.
* C compiler.
* Java 1.8 or newer to build and to instrument classes with afl-fuzz
  compatible instrumentation. Runtime Java version of instrumented
  classes should be anything that the original class worked with.
* [ASM 6.1](http://asm.ow2.org/)

Optional dependencies for building include one of these:

* Bazel

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
