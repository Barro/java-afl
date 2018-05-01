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
        public boolean found_injection_value = false;

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
                this.found_injection_value = true;
                return _data;
            }
            return value;
        }
    }

    public static void main(String args[]) throws IOException
    {
        if (args.length != 2) {
            System.err.println("Usage: JavaAflInject javafl/JavaAfl.class libjava-afl.so");
            System.exit(1);
        }

        String class_filename = args[0];
        String library_filename = args[1];
        File library = new File(library_filename);
        long library_size = library.length();
        byte library_data[] = new byte[(int)library_size];
        (new FileInputStream(library)).read(library_data);
        java.io.ByteArrayOutputStream data_output = new java.io.ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(data_output);
        gzip.write(library_data, 0, library_data.length);
        gzip.finish();
        String jni_data = data_output.toString("ISO-8859-1");
        InjectingReader reader = new InjectingReader(
            new FileInputStream(class_filename), jni_data);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        reader.accept(writer, ClassReader.SKIP_DEBUG);
        if (!reader.found_injection_value) {
            throw new RuntimeException(
                "Could not find a place to inject the data!");
        }
        byte[] bytes = writer.toByteArray();
        (new java.io.FileOutputStream(class_filename)).write(bytes);
    }
}
