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

    static {
        // TODO make it possible to load this native library from
        // inside a .jar file that JavaAfl is coming from:
        System.loadLibrary("java-afl");
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
