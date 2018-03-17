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

public class Crashing
{
    public static void main(String[] args) throws java.io.IOException
    {
        byte[] buffer = new byte[100];
        // Make this crash on very likely generated inputs but not on
        // files inside in/ directory:
        if (System.in.read(buffer) > 10) {
            throw new RuntimeException("I will crash now!");
        }
    }
}
