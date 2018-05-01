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

package test;

import java.util.HashMap;

public class Utils
{
    static public HashMap<Byte, Integer> values()
    {
        HashMap<Byte, Integer> values = new HashMap<Byte, Integer>();
        for (int i = 0; i < 'z' - 'a'; ++i) {
            byte key = (byte)('a' + i);
            values.put(key, i);
        }
        return values;
    }

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
