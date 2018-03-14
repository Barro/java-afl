package test;

import java.util.HashMap;

public class Utils
{
    static public void fuzz_one(byte[] data, int size, HashMap<Byte, Integer> values)
    {
        long total = 0;
        for (int i = 0; i < size; i++) {
            byte key = data[i];
            Integer value = values.getOrDefault(key, null);
            if (value == null) {
                continue;
            }
            if (value % 5 == 0) {
                total += value * 5;
                total += key;
            } else if (value % 3 == 0) {
                total += value * 3;
                total += key;
            } else if (value % 2 == 0) {
                total += value * 2;
                total += key;
            } else {
                total += value + key;
            }
        }
        System.out.println(total);
    }
}
