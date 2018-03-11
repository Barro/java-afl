This is a fork server based approach to fuzz Java applications on Java
virtual machine with
[american fuzzy lop](http://lcamtuf.coredump.cx/afl/). Inspired by
[python-afl](http://jwilk.net/software/python-afl).

Tested with:

* afl 2.52b
* OpenJDK 1.8.0_151

Speed:

* Fork server mode around 300 executions/second.
* Persistent mode around 10000 executions/second.

## Building

You need to have [ASM 6.1](http://asm.ow2.org/) to build this as a
dependency in addition to Java 8 and afl build dependencies. Currently
there is a crude build script to build and test this implementation:

```
$ ./build.sh
```


TODO description and easy to use implementation.
