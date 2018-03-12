public class JavaAfl
{
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    public @interface CustomInit {};

    static class Handler implements Thread.UncaughtExceptionHandler {
        public void uncaughtException(Thread t, Throwable e) {
            JavaAfl._handle_uncaught_exception();
        }
    }

    // These are functions that the instrumentation part uses:
    static private native int _get_map_size();
    public static byte map[];
    public static int prev_location;
    // If you change the string value of this, you also need to change
    // the corresponding value at JavaAflInject.java file!
    private final static String _jni_code = "<INJECT-JNI>";

    static {
        java.io.File jni_target = null;
        try {
            byte jni_code[] = _jni_code.getBytes("ISO-8859-1");
            jni_target = java.io.File.createTempFile("libjava-afl-", ".so");
            (new java.io.FileOutputStream(jni_target)).write(jni_code);
            System.load(jni_target.getAbsolutePath());
        } catch (java.io.IOException e) {
            new RuntimeException(e);
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
        JavaAfl._init(false);
    }

    static private void _init(boolean is_persistent)
    {
        _init_impl(is_persistent);
        Handler handler = new Handler();
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }

    static private native void _init_impl(boolean is_persistent);
    static public native void _handle_uncaught_exception();
    static public native void _after_main();

    static private native void _send_map();

    // Function to use in the deferred mode in combination
    // with @JavaAfl.CustomInit annotation:
    static public void init()
    {
        _init(false);
    }

    static private boolean _allow_persistent = false;
    static private int _current_iteration = 0;
    static public boolean loop(int iterations)
    {
        if (_current_iteration == 0) {
            String persistent_set = System.getenv("JAVA_AFL_PERSISTENT");
            _allow_persistent = persistent_set != null;
            _init(_allow_persistent);
            _current_iteration = 1;
            return true;
        }
        if (_allow_persistent && _current_iteration < iterations) {
            _send_map();
            _current_iteration++;
            return true;
        }
        if (_allow_persistent) {
            _send_map();
        }
        return false;
    }
}
