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

public class fuzz
{
    // This is here so that this class won't be accidentally instrumented.
    static public final String INSTRUMENTATION_MARKER = "__JAVA-AFL-INSTRUMENTED-CLASSFILE__";

    // Function to use in the deferred mode in combination
    // with @javafl.CustomInit annotation:
    static public void init()
    {
        javafl.JavaAfl._init(false);
    }

    static private boolean _allow_persistent = false;
    static private int _current_iteration = 0;
    static public boolean loop(int iterations)
    {
        if (_current_iteration == 0) {
            String persistent_set = System.getenv("JAVA_AFL_PERSISTENT");
            _allow_persistent = persistent_set != null;
            javafl.JavaAfl._init(_allow_persistent);
            _current_iteration = 1;
            return true;
        }
        if (_allow_persistent && _current_iteration < iterations) {
            javafl.JavaAfl._send_map();
            _current_iteration++;
            return true;
        }
        if (_allow_persistent) {
            javafl.JavaAfl._send_map();
        }
        return false;
    }
}
