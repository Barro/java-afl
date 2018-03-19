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

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Random;
import java.util.jar.JarInputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;


import org.objectweb.asm.ClassReader;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.*;

public class JavaAflInstrument
{
    static private int total_locations = 0;
    static private int total_jarfiles = 0;
    static private int total_classfiles = 0;

    static class InstrumentedClass
    {
        String directory;
        byte[] data;

        InstrumentedClass(byte[] data_)
        {
            directory = "";
            data = data_;
        }

        InstrumentedClass(String directory_, byte[] data_)
        {
            directory = directory_;
            data = data_;
        }
    }

    static class InstrumentationOptions
    {
        int ratio;
        boolean has_custom_init;
        boolean deterministic;

        InstrumentationOptions(int ratio_, boolean has_custom_init_, boolean deterministic_)
        {
            ratio = ratio_;
            has_custom_init = has_custom_init_;
            deterministic = deterministic_;
        }

        InstrumentationOptions(InstrumentationOptions other)
        {
            this(other.ratio, other.has_custom_init, other.deterministic);
        }
}

    static class InstrumentingMethodVisitor extends MethodVisitor
    {
        private boolean _has_custom_init;
        private int _instrumentation_ratio;
        private boolean _is_main;
        private InstrumentationOptions _options;
        private Random _random;

        public InstrumentingMethodVisitor(
            MethodVisitor mv_,
            Random random,
            InstrumentationOptions options,
            boolean is_main)
        {
            super(Opcodes.ASM6, mv_);
            _is_main = is_main;
            _random = random;
            _instrumentation_ratio = options.ratio;
            _has_custom_init = options.has_custom_init;
        }

        private void _aflMaybeLog()
        {
            JavaAflInstrument.total_locations++;
            int location_id = _random.nextInt(javafl.JavaAfl.map.length);
            // + &JavaAfl.map
            mv.visitFieldInsn(GETSTATIC, "javafl/JavaAfl", "map", "[B");
            // + location_id
            mv.visitLdcInsn(location_id);
            // + JavaAfl.prev_location
            mv.visitFieldInsn(GETSTATIC, "javafl/JavaAfl", "prev_location", "I");
            // - 2 values (location_id, prev_location)
            // + location_id ^ prev_location -> tuple_index
            mv.visitInsn(IXOR);
            // + &JavaAfl.map
            // + tuple_index
            mv.visitInsn(DUP2);
            // - 2 values (&JavaAfl.map, tuple_index)
            // + JavaAfl.map[tuple_index] -> previous_tuple_value
            mv.visitInsn(BALOAD);
            // + 1
            mv.visitInsn(ICONST_1);
            // - 2 values (1, previous_tuple_value)
            // + 1 + previous_tuple_value -> new_tuple_value
            mv.visitInsn(IADD);
            // = (byte)new_tuple_value
            mv.visitInsn(I2B);
            // - 3 values (new_tuple_value, tuple_index, &JavaAfl.map)
            // = new_tuple_value
            mv.visitInsn(BASTORE);
            // Stack modifications are now +-0 here.

            // + location_id >> 1 = shifted_location
            mv.visitLdcInsn(location_id >> 1);
            // - 1 value (shifted_location)
            mv.visitFieldInsn(PUTSTATIC, "javafl/JavaAfl", "prev_location", "I");
        }

        @Override
        public void visitCode()
        {
            mv.visitCode();
            if (_is_main && !_has_custom_init) {
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    "javafl/JavaAfl",
                    "_before_main",
                    "()V",
                    false);
            }
            if (_random.nextInt(100) < _instrumentation_ratio) {
                _aflMaybeLog();
            }
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            mv.visitJumpInsn(opcode, label);
            _aflMaybeLog();
       }

        @Override
        public void visitLabel(Label label) {
            mv.visitLabel(label);
            _aflMaybeLog();
        }

        @Override
        public void visitInsn(int opcode)
        {
            // Main gets special treatment in handling returns. It
            // can't return anything else than void:
            if (_is_main && opcode == RETURN) {
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    "javafl/JavaAfl",
                    "_after_main",
                    "()V",
                    false);
            }
            mv.visitInsn(opcode);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            // TODO it should be possible to also get the full class
            // descriptor name out during the compilation time...
            if (desc.equals("L" + javafl.CustomInit.class.getName().replace(".", "/") + ";")) {
                _has_custom_init = true;
            }
            return null;
        }
    }

    static class InstrumentingClassVisitor extends ClassVisitor
    {
        ClassWriter _writer;
        InstrumentationOptions _options;
        Random _random;

        public InstrumentingClassVisitor(
            ClassWriter cv,
            Random random,
            InstrumentationOptions options)
        {
            super(Opcodes.ASM6, cv);
            _writer = cv;
            _random = random;
            _options = options;
        }

        @Override
        public MethodVisitor visitMethod(
            int access,
            String name,
            String desc,
            String signature,
            String[] exceptions)
        {
            MethodVisitor mv = cv.visitMethod(
                access, name, desc, signature, exceptions);
            if (mv == null) {
                return null;
            }

            // Instrument all public static main functions with the
            // start-up and teardown instrumentation.
            int public_static = Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC;
            if (name.equals("main") && ((access & public_static) != 0)) {
                _writer.newMethod(
                    "javafl/JavaAfl",
                    "_before_main",
                    "()V",
                    false);
                _writer.newMethod(
                    "javafl/JavaAfl",
                    "_after_main",
                    "()V",
                    false);
                mv = new InstrumentingMethodVisitor(
                    mv, _random, _options, true);
            } else {
                mv = new InstrumentingMethodVisitor(
                    mv, _random, _options, false);
            }
            return mv;
        }
    }

    private static boolean is_instrumented(ClassReader reader)
    {
        // It would be sooo much more easy if Java had memmem() like
        // function in its standard library...
        int items = reader.getItemCount();
        byte marker_bytes[] = javafl.JavaAfl.INSTRUMENTATION_MARKER.getBytes();
        for (int i = 0 ; i < items; i++) {
            int index = reader.getItem(i);
            int item_size = reader.b[index] * 256 + reader.b[index + 1];
            if (item_size != marker_bytes.length) {
                continue;
            }
            int start = index + 2;
            int end = start + marker_bytes.length;
            if (reader.b.length < end) {
                return false;
            }
            byte value[] = Arrays.copyOfRange(reader.b, start, end);
            if (Arrays.equals(marker_bytes, value)) {
                return true;
            }
        }
        return false;
    }

    private static void instrument_classfile(
        File output_dir, String filename, InstrumentationOptions options)
    {
        File source_file = new File(filename);
        byte[] input_data = new byte[(int)source_file.length()];
        try {
            FileInputStream input_stream = new FileInputStream(source_file);
            int read = input_stream.read(input_data);
            if (read != input_data.length) {
                System.err.println("Unable to fully read " + filename);
            }
            InstrumentedClass instrumented = instrument_class(
                input_data, filename, options);
            File output_target_base = new File(
                output_dir, instrumented.directory);
            if (!output_target_base.exists()) {
                output_target_base.mkdirs();
            }
            File output_target_file = new File(
                output_target_base, source_file.getName());
            (new FileOutputStream(output_target_file)).write(instrumented.data);
            total_classfiles++;
        } catch (IOException e) {
            System.err.println("Unable to instrument " + filename + ": " + e);
        }
    }

    static class RetryableInstrumentationException extends RuntimeException
    {
        public InstrumentedClass class_data;
        RetryableInstrumentationException(InstrumentedClass class_data_)
        {
            class_data = class_data_;
        }
    }

    private static InstrumentedClass instrument_class(
        byte[] input, String filename, InstrumentationOptions options)
    {
        int old_locations = JavaAflInstrument.total_locations;
        InstrumentationOptions try_options = new InstrumentationOptions(
            options);
        // Some relatively shortlist of instrumentation ratios that
        // eventually end up in zero:
        int[] max_ratios = {80, 65, 50, 35, 25, 15, 10, 5, 2, 1, 0};
        for (int i = 0; i < 100; i++) {
            try {
                return try_instrument_class(input, filename, try_options);
            } catch (RetryableInstrumentationException e) {
                JavaAflInstrument.total_locations = old_locations;
                if (try_options.ratio == 0) {
                    System.err.println(
                        "Unable instrument " + filename + " at all!");
                    return e.class_data;
                }
                for (int new_ratio : max_ratios) {
                    if (try_options.ratio > new_ratio) {
                        try_options.ratio = new_ratio;
                        System.err.println(
                            "Instrumenting " + filename
                            + " with ratio " + new_ratio + "!");
                        break;
                    }
                }
            }
        }
        assert false : "We should never reach here! File a bug!";
        throw new AssertionError("We should never reach here! File a bug!");
    }

    protected static InstrumentedClass try_instrument_class(
        byte[] input, String filename, InstrumentationOptions options)
    {
        if (!filename.endsWith(".class")) {
            return new InstrumentedClass(input);
        }
        // Work around ClassReader bug on zero length file:
        if (input.length == 0) {
            System.err.println("Empty file: " + filename);
            return new InstrumentedClass(input);
        }
        ClassReader reader;
        try {
            reader = new ClassReader(input);
        } catch (java.lang.IllegalArgumentException e) {
            System.err.println(
                "File " + filename + " is not a valid class file.");
            return new InstrumentedClass(input);
        }
        String name = reader.getClassName();
        File directory_file = new File(name).getParentFile();
        String directory = "";
        if (directory_file != null) {
            directory = directory_file.toString();
        }
        if (is_instrumented(reader)) {
            System.err.println("Already instrumented " + filename);
            total_classfiles--;
            return new InstrumentedClass(directory, input);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        Random random;
        if (options.deterministic) {
            random = new Random(java.util.Arrays.hashCode(input));
        } else {
            random = new Random();
        }
        ClassVisitor visitor = new InstrumentingClassVisitor(
            writer, random, options);
        try {
            reader.accept(visitor, ClassReader.SKIP_DEBUG);
        } catch (java.lang.TypeNotPresentException e) {
            System.err.println(
                "Error while processing " + filename + ": " + e.getMessage());
            return new InstrumentedClass(directory, input);
        } catch (java.lang.IllegalArgumentException e) {
            System.err.println(
                "Error while processing " + filename + ": " + e.getMessage());
            return new InstrumentedClass(directory, input);
        }

        try {
            writer.newUTF8(javafl.JavaAfl.INSTRUMENTATION_MARKER);
            return new InstrumentedClass(directory, writer.toByteArray());
        } catch (java.lang.IndexOutOfBoundsException e) {
            // It's possible that the instrumentation makes the method
            // larger than 64 kilobytes that is the limit that Java
            // bytecode imposes on methods.
            throw new RetryableInstrumentationException(
                new InstrumentedClass(directory, input));
        }
    }

    private static File _instrument_jar(
        JarFile input, File output, InstrumentationOptions options) throws IOException
    {
        FileOutputStream output_jarfile = new FileOutputStream(output);
        JarOutputStream jar = new JarOutputStream(output_jarfile);
        Enumeration<? extends JarEntry> entries = input.entries();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            InputStream stream = input.getInputStream(entry);

            if (entry.isDirectory()) {
                jar.putNextEntry(new JarEntry(entry));
                continue;
            }
            InstrumentedClass instrumented = instrument_class(
                input_stream_to_bytes(stream),
                input.getName() + "/" + entry.getName(),
                options);
            byte[] instrumented_class = instrumented.data;
            jar.putNextEntry(new JarEntry(entry.getName()));
            if (instrumented_class == null) {
                jar.write(input_stream_to_bytes(stream));
            } else {
                jar.write(instrumented_class);
            }
        }
        add_JavaAfl_to_jar(jar);
        jar.close();
        return output;
    }

    private static void _instrument_file(
        File output_dir, String filename, InstrumentationOptions options)
    {
        if (filename.endsWith(".class")) {
            instrument_classfile(output_dir, filename, options);
            return;
        }
        File source_file = new File(filename);
        File physical_output = null;
        File rename_target = new File(output_dir, source_file.getName());
        try {
            JarFile jar_input = new JarFile(source_file, false);
            physical_output = File.createTempFile(
                ".java-afl-new-", ".jar", output_dir);
            _instrument_jar(
                jar_input, physical_output, options);
            physical_output.renameTo(rename_target);
            total_jarfiles++;
        } catch (java.io.FileNotFoundException e) {
            System.err.println(
                "File " + filename + " is not a valid file: " + e.getMessage());
        } catch (IOException e) {
            if (physical_output != null) {
                physical_output.delete();
            }
            System.err.println("Failed to read jar " + filename + ": " + e.getMessage());
        }
    }

    private static byte[] input_stream_to_bytes(InputStream stream)
    {
        ByteArrayOutputStream bytestream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            int read = stream.read(buffer);
            while (read > 0) {
                bytestream.write(buffer, 0, read);
                read = stream.read(buffer);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bytestream.toByteArray();
    }

    private static void add_JavaAfl_to_jar(JarOutputStream jar)
    {
        String[] filenames = {
            "javafl/CustomInit.class",
            "javafl/fuzz.class",
            "javafl/JavaAfl.class",
            "javafl/JavaAfl$1.class",
        };
        try {
            jar.putNextEntry(new JarEntry("javafl/"));
            for (String filename : filenames) {
                jar.putNextEntry(new JarEntry(filename));
                jar.write(
                    input_stream_to_bytes(
                        (InputStream)JavaAflInstrument.class.getResource("/" + filename).getContent()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void add_JavaAfl_to_directory(File directory)
    {
        String[] filenames = {
            "javafl/CustomInit.class",
            "javafl/fuzz.class",
            "javafl/JavaAfl.class",
            "javafl/JavaAfl$1.class",
        };
        try {
            for (String filename : filenames) {
                File target = new File(directory, filename);
                File class_directory = target.getParentFile();
                if (!class_directory.exists()) {
                    class_directory.mkdirs();
                }
                FileOutputStream output = new FileOutputStream(target);
                output.write(
                    input_stream_to_bytes(
                        (InputStream)JavaAflInstrument.class.getResource("/" + filename).getContent()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int usage()
    {
        System.err.println(
            "Usage: instrumentor [--custom-init]|[--deterministic]|[--] output-dir input.jar|input.class...");
        return 1;
    }

    public static void main(String args[]) throws IOException
    {
        if (args.length < 2) {
            System.exit(usage());
        }

        InstrumentationOptions options = new InstrumentationOptions(
            100, false, false);
        boolean parsing = true;
        int arg_index = -1;
        while (parsing) {
            arg_index++;
            String argument = args[arg_index];
            if (!argument.startsWith("--")) {
                parsing = false;
            } else if (argument.equals("--custom-init")) {
                options.has_custom_init = true;
            } else if (argument.equals("--deterministic")) {
                options.deterministic = true;
            } else if (argument.equals("-h") || argument.equals("--help")) {
                System.exit(usage());
            } else if (argument.equals("--")) {
                parsing = false;
            } else if (argument.startsWith("--")) {
                System.exit(usage());
            }
        }
        if (args.length <= arg_index) {
            System.exit(usage());
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

        File output_dir = new File(args[arg_index]);
        if (!output_dir.exists()) {
            if (!output_dir.mkdirs()) {
                System.err.println("Unable to create output directory!");
                return;
            }
        }
        if (!output_dir.isDirectory()) {
            System.err.println(
                "Output directory " + output_dir + " is not a directory!");
            return;
        }
        for (int i = arg_index + 1; i < args.length; i++) {
            _instrument_file(output_dir, args[i], options);
        }
        if (total_classfiles > 0) {
            add_JavaAfl_to_directory(output_dir);
        }
        System.out.println(
            "Output files are available at " + output_dir.getCanonicalPath());
        System.out.println(
            "Instrumented " + total_classfiles + " .class files and "
            + total_jarfiles + " .jar files with "
            + total_locations + " locations.");
    }
}
