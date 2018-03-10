public class JavaAfl
{
    public @interface CustomInit {};

    static class Handler implements Thread.UncaughtExceptionHandler {
        public void uncaughtException(Thread t, Throwable e) {
            JavaAfl._handle_uncaught_exception();
        }
    }

    public static final int MAP_SIZE_POW2 = 16;
    public static final int MAP_SIZE = 1 << MAP_SIZE_POW2;
    public static byte map[] = new byte[MAP_SIZE];
    public static int prev_location;

    static {
        System.loadLibrary("java-afl");
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
        return false;
    }
}
