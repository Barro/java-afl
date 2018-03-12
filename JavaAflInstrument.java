import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

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
    static private final String INSTRUMENTATION_MARKER = "__JAVA-AFL-INSTRUMENTED-CLASSFILE__";
    static private int total_locations = 0;

    static class InstrumentingMethodVisitor extends MethodVisitor
    {
        private boolean _is_main;
        private boolean _has_custom_init;
        private Random _random;

        public InstrumentingMethodVisitor(MethodVisitor mv_, boolean is_main)
        {
            super(Opcodes.ASM6, mv_);
            _is_main = is_main;
            _random = new Random();
            _has_custom_init = false;
        }

        private void _aflMaybeLog()
        {
            JavaAflInstrument.total_locations++;
            int location_id = _random.nextInt(JavaAfl.map.length);
            // + &JavaAfl.map
            mv.visitFieldInsn(GETSTATIC, "JavaAfl", "map", "[B");
            // + location_id
            mv.visitLdcInsn(location_id);
            // + JavaAfl.prev_location
            mv.visitFieldInsn(GETSTATIC, "JavaAfl", "prev_location", "I");
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
            mv.visitFieldInsn(PUTSTATIC, "JavaAfl", "prev_location", "I");
        }

        @Override
        public void visitCode()
        {
            mv.visitCode();
            if (_is_main && !_has_custom_init) {
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    "JavaAfl",
                    "_before_main",
                    "()V",
                    false);
            }
            _aflMaybeLog();
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
                    "JavaAfl",
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
            if (desc.equals("L" + JavaAfl.CustomInit.class.getName() + ";")) {
                _has_custom_init = true;
            }
            return null;
        }
    }

    static class InstrumentingClassVisitor extends ClassVisitor
    {
        ClassWriter _writer;

        public InstrumentingClassVisitor(ClassWriter cv)
        {
            super(Opcodes.ASM6, cv);
            _writer = cv;
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
                    "JavaAfl",
                    "_before_main",
                    "()V",
                    false);
                _writer.newMethod(
                    "JavaAfl",
                    "_after_main",
                    "()V",
                    false);
                mv = new InstrumentingMethodVisitor(mv, true);
            } else {
                mv = new InstrumentingMethodVisitor(mv, false);
            }
            return mv;
        }
    }

    private static boolean has_instrumentation(ClassReader reader)
    {
        // It would be sooo much more easy if Java had memmem() like
        // function in its standard library...
        int items = reader.getItemCount();
        byte marker_bytes[] = INSTRUMENTATION_MARKER.getBytes();
        for (int i = 0 ; i < items; i++) {
            int index = reader.getItem(i);
            int item_size = reader.b[index] * 256 + reader.b[index + 1];
            if (item_size != marker_bytes.length) {
                continue;
            }
            byte value[] = Arrays.copyOfRange(
                reader.b, index + 2, index + 2 + marker_bytes.length);
            if (Arrays.equals(marker_bytes, value)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String args[]) throws IOException
    {
        if (args.length < 1) {
            System.err.println("Usage: instrumentor classfile...");
            return;
        }

        for (String filename : args) {
            ClassReader reader = new ClassReader(
                new FileInputStream(filename));
            if (has_instrumentation(reader)) {
                System.err.println("Already instrumented " + filename);
                continue;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            ClassVisitor visitor = new InstrumentingClassVisitor(writer);
            reader.accept(visitor, ClassReader.SKIP_DEBUG);
            writer.newUTF8(INSTRUMENTATION_MARKER);
            byte[] bytes = writer.toByteArray();
            (new java.io.FileOutputStream(filename)).write(bytes);
            System.out.println(
                "Instrumented " + total_locations + " locations: " + filename);
        }
    }
}
