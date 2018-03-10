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
        JavaAfl._before_main(false);
        Handler handler = new Handler();
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }

    static public native void _before_main(boolean is_persistent);
    static public native void _handle_uncaught_exception();
    static public native void _after_main();
}
