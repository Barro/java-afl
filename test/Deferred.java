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

public class Deferred
{
    public static void main(String[] args) throws java.io.IOException
    {
        HashMap<Byte, Integer> values = new HashMap<Byte, Integer>();
        for (int i = 0; i < 'z' - 'a'; ++i) {
            byte key = (byte)('a' + i);
            values.put(key, i);
        }
        byte[] data = new byte[128];
        int read = 128;
        javafl.fuzz.init();
        if (args.length >= 1) {
            read = (new java.io.FileInputStream(args[0])).read(data, 0, data.length);
        } else {
            read = System.in.read(data, 0, data.length);
        }
        test.Utils.fuzz_one(data, read, values);
    }
}
