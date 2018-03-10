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

TODO description and easy to use implementation.
