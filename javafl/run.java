package javafl;

import java.util.HashMap;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import javafl.JavaAflInstrument;

public class run extends ClassLoader
{
    HashMap<String, Class<?>> _cache;

    public run(ClassLoader parent) {
        super(parent);
        _cache = new HashMap<String, Class<?>>();
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        if (name.startsWith("java.")) {
            return super.loadClass(name);
        }

        if (_cache.containsKey(name)) {
            Class<?> cached_class = _cache.get(name);
            // This likely means that there are other classes that
            // result in security exceptions that java.* package
            // related.
            if (cached_class == null) {
                return super.loadClass(name);
            }
            return cached_class;
        }
        String resource = name.replace(".", "/") + ".class";
        InputStream stream = getResourceAsStream(resource);
        if (stream == null) {
            throw new ClassNotFoundException("Could not load class " + name);
        }
        ByteArrayOutputStream class_buffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            int read = stream.read(buffer, 0, buffer.length);
            while (read > 0) {
                class_buffer.write(buffer, 0, read);
                read = stream.read(buffer, 0, buffer.length);
            }
        } catch (java.io.IOException e) {
            // System.out.println("foo " + e);
            return super.loadClass(name);
        }
        byte[] class_data = class_buffer.toByteArray();
        JavaAflInstrument.InstrumentationOptions options =
            new JavaAflInstrument.InstrumentationOptions(
                100, false, true);
        JavaAflInstrument.InstrumentedClass instrumented = JavaAflInstrument.try_instrument_class(
            class_data, resource, options);
        // System.out.println("bytes: " + class_data.length + " vs. " + instrumented.data.length);
        try {
            Class<?> result = defineClass(name, instrumented.data, 0, instrumented.data.length);
            _cache.put(name, result);
            return result;
        } catch (java.lang.SecurityException e) {
            return super.loadClass(name);
        }
    }

    public static void main(String[] args) throws
        ClassNotFoundException,
        NoSuchMethodException,
        IllegalAccessException,
        java.lang.reflect.InvocationTargetException
    {
        if (args.length < 1) {
            System.err.println(
                "Usage: java-afl-run main.Class [args-to-main.Class]...");
            System.exit(1);
        }
        int map_size = javafl.JavaAfl.map.length;
        ClassLoader my_loader = run.class.getClassLoader();
        ClassLoader loader = new run(my_loader);
        Class<?> clazz = null;
        try {
            clazz = loader.loadClass(args[0]);
        } catch (ClassNotFoundException e) {
            System.err.println(
                "No class " + args[0] + " found! Make sure that it can be found from the CLASSPATH: " + e.getMessage());
            System.exit(1);
        }
        java.lang.reflect.Method main_method = clazz.getMethod(
            "main", args.getClass());
        if (main_method == null) {
            System.err.println(
                "No main(String[]) method found for class " + args[0]);
            System.exit(1);
        }
        String[] new_args = java.util.Arrays.copyOfRange(
            args, 1, args.length);
        main_method.invoke(null, (Object)new_args);
    }
}
