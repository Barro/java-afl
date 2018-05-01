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

/**
 * Public user facing interface to initialization functions
 *
 * End-users of this library should call these functions if they want
 * to optimize their 
 */
public class fuzz
{
    // This is here so that this class won't be accidentally instrumented.
    static public final String INSTRUMENTATION_MARKER = "__JAVA-AFL-INSTRUMENTED-CLASSFILE__";

    /**
     * In forking mode makes new children to start at the calling
     * point of this function
     *
     * Usage:
     *
     * ... do initial configuration here...
     * javafl.fuzz.init();
     * read_bytes = System.in.read(data_buffer);
     * ... do actual fuzzing here...
     *
     * It is recommended that you use fuzz.loop() function in the
     * surrogate mode instead of fuzz.init() function in forking
     * mode. Only time this makes sense if the program has a global
     * state that you want to reset before every fuzzing iteration to
     * gain stability.
     */
    public static void init()
    {
        javafl.JavaAfl._init_deferred();
    }

    /**
     * Marks a loop to do fuzzing iterations repeatedly
     *
     * Usage:
     *
     * ... do initial configuration here...
     * while (javafl.fuzz.loop()) {
     *     read_bytes = System.in.read(data_buffer);
     *     ... do actual fuzzing here...
     * }
     *
     * This prevents the program to restart between fuzzing
     * iterations. When combined with the default surrogate mode, this
     * provides the lowest overhead for fuzzing with tens of thousands
     * of possible iterations per second for trivial fuzz targets.
     */
    public static boolean loop()
    {
        return javafl.JavaAfl._persistent_mode_loop();
    }

    /**
     * Marks a loop to do fuzzing iterations repeatedly until a
     * certain point
     * 
     * Usage:
     *
     * ... do initial configuration here...
     * while (javafl.fuzz.loop(10000)) {
     *     read_bytes = System.in.read(data_buffer);
     *     ... do actual fuzzing here...
     * }
     *
     * This prevents the program to restart between fuzzing
     * iterations until there have been the requested amount of
     * iterations. You should use the fuzz.loop() function without
     * numerical arguments in the default surrogate mode to get the
     * highest performance.
     */
    public static boolean loop(long iterations)
    {
        if (iterations < 1) {
            throw new IllegalArgumentException(
                "Expected persistent mode loop to have at least 1 iteration!");
        }
        return javafl.JavaAfl._persistent_mode_loop(iterations);
    }
}
