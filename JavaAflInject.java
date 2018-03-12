import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class JavaAflInject
{
    static class InjectingReader extends ClassReader
    {
        private String _data;

        public InjectingReader(
            FileInputStream file, String data) throws IOException
        {
            super(file);
            if (data.length() > 65535) {
                // TODO Java's constant pool limits the size of each
                // string constant to 64 kilobytes. This could be
                // worked around by combining multiple values during
                // runtime. It's actually quite possible to exceed
                // this by having plenty of debugging data added.
                throw new IllegalArgumentException(
                    "Injected value can not exceed 64 kilobytes!");
            }
            _data = data;
        }

        @Override
        public String readUTF8(int item, char[] buf)
        {
            String value = super.readUTF8(item, buf);
            if (value == null) {
                return null;
            }
            if (value.equals("<INJECT-JNI>")) {
                return _data;
            }
            return value;
        }
    }

    public static void main(String args[]) throws IOException
    {
        if (args.length != 2) {
            System.err.println("Usage: JavaAflInject JavaAfl.class libjava-afl.so");
            return;
        }

        String class_filename = args[0];
        String library_filename = args[1];
        File library = new File(library_filename);
        long library_size = library.length();
        byte library_data[] = new byte[(int)library_size];
        (new FileInputStream(library)).read(library_data);
        String jni_data = (java.util.Base64.getEncoder()).encodeToString(library_data);
        ClassReader reader = new InjectingReader(
            new FileInputStream(class_filename), jni_data);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        reader.accept(writer, ClassReader.SKIP_DEBUG);
        byte[] bytes = writer.toByteArray();
        (new java.io.FileOutputStream(class_filename)).write(bytes);
    }
}
