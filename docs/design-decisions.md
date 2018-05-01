This document outlines some invisible design decisions and failed
trials that are not obvious from the final code.

## ByteBuffer usage

Directly using the shared memory area that afl-fuzz allocates would
make it possible to avoid copying data from Java's allocated byte
array to the shared memory after each iteration.

Allocating a ByteBuffer object that includes the shared memory data at
JNI side and then getting a byte[] reference to the actual shared
memory area by ByteBuffer.array() function does not work. Maybe some
performance related trials with ByteBuffer.get() and ByteBuffer.put()
functions versus the _map byte array index references.

## Instrumentation styles

Byte code modification currently is designed for the believed speed in
similar way that afl-as does, not for size. This distinction becomes
relevant with large methods that result in over 64 kilobytes of
bytecode after modification. Performance related tests are still
something TODO, as theoretically JVM could easily inline all static
function calls away. This is not possible when you are modifying the
assembly instructions that have already all the modifications that
an ahead of time compiler does for them.
