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

import java.util.HashMap;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import javafl.JavaAflInstrument;

public class run extends ClassLoader
{
    private HashMap<String, Class<?>> _cache;
    private JavaAflInstrument.InstrumentationOptions _options;

    public run(
        ClassLoader parent,
        JavaAflInstrument.InstrumentationOptions options) {
        super(parent);
        _cache = new HashMap<String, Class<?>>();
        _options = options;
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
        JavaAflInstrument.InstrumentedClass instrumented = JavaAflInstrument.try_instrument_class(
            class_data, resource, _options);
        // System.out.println("bytes: " + class_data.length + " vs. " + instrumented.data.length);
        try {
            Class<?> result = defineClass(name, instrumented.data, 0, instrumented.data.length);
            _cache.put(name, result);
            return result;
        } catch (java.lang.SecurityException e) {
            return super.loadClass(name);
        }
    }

    private static int usage()
    {
        System.err.println(
            "Usage: java-afl-run [--custom-init] main.Class [args-to-main.Class]...");
        return 1;
    }

    public static void main(String[] args) throws
        ClassNotFoundException,
        NoSuchMethodException,
        IllegalAccessException,
        java.lang.reflect.InvocationTargetException
    {
        if (args.length < 1) {
            System.exit(usage());
        }

        JavaAflInstrument.InstrumentationOptions options =
            new JavaAflInstrument.InstrumentationOptions(
                100, false, true);

        int arg_index = 0;
        String argument = args[arg_index];
        if (argument.equals("--custom-init")) {
            options.has_custom_init = true;
            arg_index++;
        }

        String ratio_str = System.getenv("AFL_INST_RATIO");
        if (ratio_str != null) {
            options.ratio = Integer.parseInt(ratio_str);
        }
        ratio_str = System.getenv("JAVA_AFL_INST_RATIO");
        if (ratio_str != null) {
            options.ratio = Integer.parseInt(ratio_str);
        }
        if (options.ratio < 0 || options.ratio > 100) {
            System.err.println("AFL_INST_RATIO must be between 0 and 100!");
            System.exit(1);
        }

        if (args.length <= arg_index) {
            System.exit(usage());
        }
        int map_size = javafl.JavaAfl.map.length;
        ClassLoader my_loader = run.class.getClassLoader();
        ClassLoader loader = new run(my_loader, options);
        Class<?> clazz = null;
        String class_name = args[arg_index];
        try {
            clazz = loader.loadClass(class_name);
        } catch (ClassNotFoundException e) {
            System.err.println(
                "No class " + class_name + " found! Make sure that it can be found from the CLASSPATH: " + e.getMessage());
            System.exit(1);
        }
        java.lang.reflect.Method main_method = clazz.getMethod(
            "main", args.getClass());
        if (main_method == null) {
            System.err.println(
                "No main(String[]) method found for class " + class_name);
            System.exit(1);
        }
        String[] new_args = java.util.Arrays.copyOfRange(
            args, arg_index + 1, args.length);
        main_method.invoke(null, (Object)new_args);
    }
}
