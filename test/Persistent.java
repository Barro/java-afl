package test;

import java.util.HashMap;

public class Persistent
{
    @javafl.CustomInit
    public static void main(String[] args) throws java.io.IOException
    {
        HashMap<Byte, Integer> values = new HashMap<Byte, Integer>();
        for (int i = 0; i < 'z' - 'a'; ++i) {
            byte key = (byte)('a' + i);
            values.put(key, i);
        }
        byte[] data = new byte[128];
        int read = 128;
        while (javafl.JavaAfl.loop(100000)) {
            if (args.length >= 1) {
                read = (new java.io.FileInputStream(args[0])).read(data, 0, data.length);
            } else {
                read = System.in.read(data, 0, data.length);
                // Throw away all buffering information from stdin:
                System.in.skip(9999999);
            }
            test.Utils.fuzz_one(data, read, values);
        }
    }
}
