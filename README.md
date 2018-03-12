This is a fork server based approach to fuzz Java applications on Java
virtual machine with [american fuzzy lop](http://lcamtuf.coredump.cx/afl/).

Tested with:

* afl 2.52b
* OpenJDK 1.8.0_151

Performance on Intel Core i7-3770K CPU @ 3.50GHz:

* Fork server mode around 750 executions/second for a program that
  does nothing. Closer to 300 when there is actually something
  happening.
* Deferred mode naturally gets something between the fork server mode
  and persistent mode. Depends how heavy the initialization is,
  probably maybe some tens of percents.
* Persistent mode around 14000 executions/second. Highly depends on
  how much and how long JVM is able to optimize before being
  killed. Around 31000 iterations/second for an empty while loop, that
  is close to the maximum that native C code can handle with afl-fuzz
  in persistent mode.

## Usage

TODO description and easy to more use implementation.

This works by instrumenting the compiled Java bytecode with
probabilistic program coverage revealing instrumentation. The default
fork server mode does not need any modifications to the program source
code and can work as is. There are also more efficient deferred fork
server and persistent modes that enable you to skip some
initialization code and keep the JVM running longer than for just one
input.

Instrumentation is done by running JavaAflInstrument program for each
class file to instrument:

```
$ export CLASSPATH=asm-6.1.jar:out/
$ java -Djava.library.path=out/ JavaAflInstrument out/ClassToInstrument.class
```

Using persistent or deferred modes requires a special annotation for
the `main()` function:

```java
public class ProgramPersistent {
    @JavaAfl.CustomInit
    public static void main(String args[]) {
        ...
        byte[] data = new byte[128];
        int read = 128;
        while (JavaAfl.loop(100000)) {
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

Deferred mode does not need that many tricks as the persistent mode:

```java
public class ProgramPersistent {
    @JavaAfl.CustomInit
    public static void main(String args[]) {
        ...
        JavaAfl.init();
        ... do actual input processing...
    }
}
```

Fuzz. JVM allocates on my Debian system around 10 gigabytes of virtual
memory, so default virtual memory limits of afl-fuzz need to be set
higher (`-m 20000`).

```
$ java-afl-fuzz -m 20000 -i in/ -o out/ -- java -Djava.library.path=out/ ClassToInstrument
```

You may want to adjust maximum heap size with
[`-Xmx`](https://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm#PERFM164)
option to be smaller than the default if you fuzz multiple JVM
instances on the same machine, as default operating system provided
memory limits won't help you here.

## Building

You need to have [ASM 6.1](http://asm.ow2.org/) to build this as a
dependency in addition to Java 8 and afl build dependencies. Currently
there is a crude build script to build and test this implementation:

```
$ ./build.sh
```

TODO description and more easy to use implementation.

## TODO

* Instrument full jar files instead of individual class files.
  * Include JNI code as part of JavaAfl class file.
  * Include JavaAfl as part of instrumented .jar file.
* Detect if a class file is already instrumented. This now does
  nothing to prevent double-instrumentation and that leads to
  immediate abort.
* Better way to build this. Multiple different build tools are
  probably a must.
* Alternative method implementations based on fuzzing mode (similar to
  C preprocessor's #ifdef/#ifndef). Probably somehow with annotations.

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
