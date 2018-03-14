package test;

import java.util.HashMap;

public class Deferred
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
        javafl.JavaAfl.init();
        if (args.length >= 1) {
            read = (new java.io.FileInputStream(args[0])).read(data, 0, data.length);
        } else {
            read = System.in.read(data, 0, data.length);
        }
        test.Utils.fuzz_one(data, read, values);
    }
}
