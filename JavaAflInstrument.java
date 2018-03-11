import org.objectweb.asm.ClassReader;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.util.Random;

import static org.objectweb.asm.Opcodes.*;

public class JavaAflInstrument
{
    static int total_locations = 0;

    static class InstrumentingMethodVisitor extends MethodVisitor
    {
        boolean _is_main;
        private Random _random;
        public InstrumentingMethodVisitor(MethodVisitor mv_, boolean is_main)
        {
            super(Opcodes.ASM6, mv_);
            _is_main = is_main;
            _random = new Random();
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
            if (_is_main) {
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
                return mv;
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

    public static void main(String args[]) throws java.io.IOException
    {
        if (args.length < 2) {
            System.err.println("Usage: instrumentor in out");
            return;
        }

        ClassReader reader = new ClassReader(
            new java.io.FileInputStream(args[0]));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new InstrumentingClassVisitor(writer);
        reader.accept(visitor, ClassReader.SKIP_DEBUG);
        byte[] bytes = writer.toByteArray();
        (new java.io.FileOutputStream(args[1])).write(bytes);
        System.out.println("Instrumented " + total_locations + " locations");
    }
}
