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

import java.security.Permission;
import java.util.concurrent.Semaphore;
import java.nio.ByteBuffer;

public class JavaAfl
{
    // This is here so that this class won't be accidentally instrumented.
    public static final String INSTRUMENTATION_MARKER = "__JAVA-AFL-INSTRUMENTED-CLASSFILE__";

    // If you change the string value of this, you also need to change
    // the corresponding value at JavaAflInject.java file!
    // This enables only passing 64 kilobytes of data. It is more than
    // enough with the help of gzip compression on Linux even when
    // there is tons of debugging data added to the resulting JNI
    // library.
    private static final String _jni_code = "<INJECT-JNI>";

    // Map size link between C code Java:
    private static native int _get_map_size();

    private static ByteBuffer _map_object;
    // These are fields that the instrumentation part uses to do its thing:
    // Original map holds the byte array that includes all traces.
    private static byte[] _map_original;

    // map field has by default the same array as _map_original. If
    // there is a timeout situation, this will get value null so that
    // the main thread has bigger chances to crash. Then it is
    // restored the value that _map_original has.
    public static volatile byte[] map;

    // prev_location includes information where program was in the
    // previous instrumentation step. This is combined with the
    // current location to produce a new map value (tuple).
    public static int prev_location;
    // This holds a note what was the previous location value at
    // initialization. It makes it possible to reset the state of
    // fuzzing whenever a new persistent mode execution starts.
    private static int _prev_location_init;

    /* TODO ByteBuffer put/get benchmarks
    private static native ByteBuffer _get_map_memory_area();*/

    // These communicate initialization data from JNI side to Java side:
    private static final int INIT_HAS_SHM = 1 << 0;
    private static final int INIT_HAS_FORKSERVER = 1 << 1;

    // Variable that decides if we even need to care about result
    // reporting.
    private static boolean _report_results = false;

    // In surrogate mode this monitors a surrogate process that gets
    // killed by afl-fuzz in case it decides that the child process
    // has timed out.
    private static ForkSurrogateMonitor _fork_surrogate_monitor;

    // Locks and variables to synchronize between fork surrogate
    // monitor thread and the main thread.
    private static Semaphore _status_report_lock;
    private static Semaphore _fork_surrogate_lock;
    private static volatile long _fork_surrogate_pid;
    private static volatile int _fork_surrogate_wstatus;

    private static boolean _is_surrogate_mode = false;
    private static boolean _is_forkserver_mode = false;

    // General variables that control how some details of
    // instrumentation behave.
    private static boolean _can_run_persistent = false;
    private static boolean _run_persistent = false;
    private static boolean _is_surrogate_loop_initialized = false;

    private static boolean _is_persistent_loop_initialized = false;
    private static long _persistent_loop_iteration = 0;

    private static native int _jni_init(long class_id);

    // Forkserver mode functions:
    private static native void _jni_init_forkserver_mode();
    // These exists should only be concern in the forkserver mode.
    private static native void _force_exit_forked_child(int status);

    // Surrogate mode functions:
    private static native void _jni_init_surrogate_mode();
    private static native long _new_fork_surrogate();
    private static native int _wait_for_fork_surrogate(long pid);
    // Report to afl-fuzz that a surrogate child has died.
    private static native void _send_child_killed_status(int status);

    // We have had a normal fuzzing iteration. Nothing special.
    private static native void _send_child_ok_status();
    // Inform afl-fuzz that we have a crash.
    private static native void _send_uncaught_exception_status();

    static {
        // These are only useful in the surrogate mode, but we can
        // just avoid conditional code by having them always
        // available.
        _status_report_lock = new Semaphore(1);
        _fork_surrogate_lock = new Semaphore(1);

        // Here we load the embedded JNI library that we inject into
        // this class at build time. This makes it possible to avoid
        // the nasty dynamic library loading that almost all JNI
        // guides advocate for, as that is the "proper" way to do
        // things.
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
        /* TODO ByteBuffer put/get benchmarks
        _map_object = _get_map_memory_area();
        if (_map_object == null) {
            // Fallback in case there is no shared memory.
            _map_original = new byte[_get_map_size()];
        } else {
            assert _map_object.isDirect();
            _map_original = _map_object.array();
        }
        */
        _map_original = new byte[_get_map_size()];
        map = _map_original;
    }

    public static class SurrogateExitSecurityException extends SecurityException
    {
    }

    static class ExitHandlingSecurityManager extends SecurityManager
    {
        @Override
        public void checkExit(int status)
        {
            if (_is_surrogate_mode) {
                // _surrogate_mode_loop() function will handle status
                // reporting.
                throw new SurrogateExitSecurityException();
            }
            _report_child_ok_status();
            // In forkserver mode we need to do a special exit, as
            // otherwise this process will hang while waiting for
            // non-existent threads to join.
            if (_is_forkserver_mode) {
                _force_exit_forked_child(status);
            }
            // When we are not fuzzing, just let the System.exit() to
            // run normally.
        }

        // Everything else than exit is allowed!
        @Override
        public void checkPermission(Permission perm)
        {
        }

        @Override
        public void checkPermission(Permission perm, Object context)
        {
        }
    }

    static class ForkSurrogateMonitor extends Thread
    {
        private Thread _main_thread;

        public ForkSurrogateMonitor(Thread main_thread)
        {
            _main_thread = main_thread;
        }

        @Override
        // Suppress warnings about Thread.stop() method usage. It's
        // useful in this context and there are no good alternatives
        // for it.
        @SuppressWarnings("deprecation")
        public void run() {
            assert _is_surrogate_mode;
            while (true) {
                try {
                    _fork_surrogate_lock.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                JavaAfl._fork_surrogate_wstatus =
                    JavaAfl._wait_for_fork_surrogate(
                        JavaAfl._fork_surrogate_pid);
                try {
                    _status_report_lock.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                // Surrogate process has died for some reason. The
                // wstatus will propagate back to afl-fuzz through the
                // main thread's exception handler.
                JavaAfl._fork_surrogate_pid = 0;
                // Make the map inaccessible to instrumentation. This
                // will cause an exception immediately when
                // instrumentation tries to access it.
                JavaAfl.map = null;
                // Use Thread.stop() to abort infinite loops in the
                // main thread that are not necessarily
                // instrumented. This method is apparently deprecated
                // and evil, but it's a necessary evil to get timeout
                // handling to work properly under more cases than
                // what Thread.interrupt() can handle.
                _main_thread.stop();
                _status_report_lock.release();
            }
        }
    }

    /**
     * Makes sure that System.in.read() does not return buffered data.
     *
     * afl-fuzz resets the standard input after each iteration. This
     * works fine if you do a raw read() call to the standard input
     * file number during each iteration and have no buffering.
     * System.in, on the other hand, has some internal buffering in
     * place and that buffer needs to be flushed before we can expect
     * the System.in.read() function work as expected in the
     * surrogate mode and in the persistent loop mode.
     */
    private static void _reset_stdin()
    {
        try {
            // Skip over 16 megabytes of data. This much should
            // hopefully never be left on the standard input buffers
            // after executing the program for one round. Especially
            // as this would require increasing the maximum pipe
            // buffer size from the default 4 or 64 kilobytes to much
            // more.
            System.in.skip(256 * 65536);
        } catch (java.io.IOException e) {
            // It is possible to end up here if the standard input is
            // fully read and we have nothing to skip over.
        }
    }

    static private void _handle_fork_surrogate_death()
    {
        JavaAfl._send_child_killed_status(JavaAfl._fork_surrogate_wstatus);
        JavaAfl.map = _map_original;
        JavaAfl._fork_surrogate_pid = _new_fork_surrogate();
        _fork_surrogate_lock.release();
    }

    @SuppressWarnings("deprecation")
    public static boolean handle_exception(Throwable caught_exception)
    {
        try {
            _status_report_lock.acquire();
            caught_exception.printStackTrace(System.err);
            if (!_report_results) {
                _force_exit_forked_child(1);
            }
            // Fork surrogate has died and done its tricks to make this
            // thread to die. Report this to parent and start a new one.
            if (_is_surrogate_mode && _fork_surrogate_pid == 0) {
                _handle_fork_surrogate_death();
            } else {
                _send_uncaught_exception_status();
                /*
                if (!JavaAfl._send_uncaught_exception_status()) {
                    // We are in a state where the child has been
                    // killed and we have detected it by receiving a
                    // message from a parent, but we haven't gotten
                    // yet any uncaught exceptions. Release the lock
                    // and Block this thread as there should be soon
                    // coming a ThreadDeath exception to kill us.
                    try {
                        _status_report_lock.release();
                        _status_report_lock.wait();
                    } catch (ThreadDeath e) {
                        if (JavaAfl._fork_surrogate_pid != 0) {
                            System.err.println("Something went wrong as fork surrogate pid was not 0!");
                            throw new RuntimeException(e);
                        }
                        _handle_fork_surrogate_death();
                        return;
                    }
                }
                */
            }
            _status_report_lock.release();
        } catch (InterruptedException e) {
            assert _is_surrogate_mode;
            if (JavaAfl._fork_surrogate_pid == 0) {
                _handle_fork_surrogate_death();
            } else {
                throw new RuntimeException(e);
            }
        }
        if (_is_surrogate_mode) {
            return true;
        }
        _force_exit_forked_child(1);
        // This line is never reached but makes the compiler happy.
        return false;
    }

    public static boolean _surrogate_mode_loop()
    {
        if (!_is_surrogate_mode) {
            return false;
        }
        // Reset the state for the persistent mode loop to treat it as
        // a clean program start-up.
        _persistent_mode_loop_reset();
        _report_child_ok_status();
        _reset_stdin();
        return true;
    }

    public static void _before_main()
    {
        // handle init:
        // * surrogate mode
        // * forkserver mode
        //   * start a forkserver here with the possibility to move it
        //     to _init_deferred function.
        //
        // * Fuzzing mode vs. non-fuzzing mode.
        //   * afl-showmap
        // * Exit handlers.
        // Do everything here that should only need to be done once.

        // class_id puts something to the initial map so that we get a
        // nice almost clean initial map that is stable between
        // program executions. And then completely empty programs get
        // at least one value to the map.
        String caller_class_name = Thread.currentThread().getStackTrace()[2].getClassName();
        System.err.println(caller_class_name);
        int class_id = caller_class_name.hashCode();
        int init_flags = _jni_init(class_id);
        boolean has_shm = (init_flags & INIT_HAS_SHM) != 0;
        _report_results = has_shm;
        // Something is listening for the results. Make sure that
        // System.exit() calls will get caught.
        if (has_shm) {
            System.setSecurityManager(new ExitHandlingSecurityManager());
        }
        boolean has_forkserver = (init_flags & INIT_HAS_FORKSERVER) != 0;

        // Nothing is repeatedly listening to our shared memory
        // modifications. No need to worry about selecting different
        // modes.
        if (!has_forkserver) {
            return;
        }

        _can_run_persistent = System.getenv("JAVA_AFL_PERSISTENT") != null;
        boolean run_forkserver = System.getenv("JAVA_AFL_FORKSERVER") != null;

        if (!_can_run_persistent || run_forkserver) {
            _is_forkserver_mode = true;
            _jni_init_forkserver_mode();
        } else {
            _is_surrogate_mode = true;
            _jni_init_surrogate_mode();

            _fork_surrogate_pid = _new_fork_surrogate();
            _fork_surrogate_monitor = new ForkSurrogateMonitor(
                Thread.currentThread());
            _fork_surrogate_monitor.start();
        }
    }

    public static void _after_main()
    {
        assert !_is_surrogate_mode : "main() function should never exit the fork surrogate mode!";

        System.err.println("after main\n");
        if (_report_results) {
            System.err.println("reporting ok\n");
            _report_child_ok_status();
        }
        // Forked child will hang at exit, as it waits for threads
        // that never got duplicated to finish. So just force exit at
        // JNI side for the child process.
        if (_is_forkserver_mode) {
            System.err.println("exiting fork\n");
            _force_exit_forked_child(0);
        }
    }

    protected static void _init_deferred()
    {
        // There is no support for deferred initialization in the
        // surrogate mode.
        if (_is_surrogate_mode) {
            return;
        }
        // fork server mode
        // child mode
    }

    private static void _persistent_mode_loop_reset()
    {
        _is_persistent_loop_initialized = false;
        _persistent_loop_iteration = 0;
    }

    protected static boolean _persistent_mode_loop()
    {
        // This tricky if() prevents the persistent mode loop from
        // reporting its status in the first iteration and still
        // repeatedly run the persistent mode loop when such status is
        // requested.
        if (!_run_persistent) {
            if (_is_persistent_loop_initialized) {
                return false;
            }
            _run_persistent = _can_run_persistent;
            _is_persistent_loop_initialized = true;
            return true;
        }
        _report_child_ok_status();
        _reset_stdin();
        return true;
    }

    protected static boolean _persistent_mode_loop(long iterations)
    {
        assert iterations > 0;
        assert _persistent_loop_iteration < iterations;
        _persistent_loop_iteration++;
        return (_persistent_mode_loop()
                && _persistent_loop_iteration != iterations);
    }

    protected static void _report_child_ok_status()
    {
        // Protect status reporting from sudden death by
        // ForkSurrogateMonitor's thread stopping.
        //
        // In fork server mode we still have these semaphores, but we
        // don't need to care about any exceptions happening, as there
        // are no other threads running at that time.
        try {
            _status_report_lock.acquire();
            _send_child_ok_status();
            _status_report_lock.release();
        } catch (InterruptedException e) {
            assert _is_surrogate_mode;
            if (JavaAfl._fork_surrogate_pid == 0) {
                _handle_fork_surrogate_death();
            } else {
                // This should not be caught by the general Throwable
                // handler that is only inside persistent mode loop.
                throw new RuntimeException(e);
            }
        }
    }
}
