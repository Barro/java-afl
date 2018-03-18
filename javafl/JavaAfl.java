/**
 * Copyright 2018  Jussi Judin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javafl;

public class JavaAfl implements Thread.UncaughtExceptionHandler
{
    // This is here so that this class won't be accidentally instrumented.
    static public final String INSTRUMENTATION_MARKER = "__JAVA-AFL-INSTRUMENTED-CLASSFILE__";

    public void uncaughtException(Thread t, Throwable e)
    {
        javafl.JavaAfl._handle_uncaught_exception();
        e.printStackTrace(System.err);
        System.exit(1);
    }

    // Map size link between C code Java:
    static private native int _get_map_size();

    // These are fields that the instrumentation part uses to do its thing:
    public static byte map[];
    public static int prev_location;

    // If you change the string value of this, you also need to change
    // the corresponding value at JavaAflInject.java file!
    // This enables only passing 64 kilobytes of data. It is more than
    // enough with the help of gzip compression on Linux even when
    // there is tons of debugging data added to the resulting JNI
    // library.
    private final static String _jni_code = "<INJECT-JNI>";

    static {
        java.io.File jni_target = null;
        try {
            byte jni_code_compressed[] = _jni_code.getBytes("ISO-8859-1");
            java.util.zip.GZIPInputStream input = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(jni_code_compressed));
            jni_target = java.io.File.createTempFile("libjava-afl-", ".so");
            java.io.FileOutputStream output = new java.io.FileOutputStream(jni_target);
            byte buffer[] = new byte[4096];
            int read = input.read(buffer, 0, buffer.length);
            while (read > 0) {
                output.write(buffer, 0, read);
                read = input.read(buffer, 0, buffer.length);
            }
            System.load(jni_target.getAbsolutePath());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (jni_target != null) {
                // We need to explicitly delete a file here instead of
                // using File.deleteOnExit(), as the JNI
                // instrumentation can exit from JVM without running
                // exit handlers.
                jni_target.delete();
            }
        }
        map = new byte[_get_map_size()];
    }

    static public void _before_main()
    {
        javafl.JavaAfl._init(false);
    }

    static protected void _init(boolean is_persistent)
    {
        _init_impl(is_persistent);
        JavaAfl handler = new JavaAfl();
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // TODO bad exit combination when in persistent mode...
                JavaAfl._after_main();
            }
        });
    }

    static protected native void _init_impl(boolean is_persistent);
    static public native void _handle_uncaught_exception();
    static public native void _after_main();

    static protected native void _send_map();
}
