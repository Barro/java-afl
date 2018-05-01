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

public class RawAllLoop
{
    private static void execute(Runnable runnable)
    {
        javafl.JavaAfl._before_main();
        boolean running = true;
        while (running) {
            try {
                running = javafl.JavaAfl._surrogate_mode_loop();
                try {
                    runnable.run();
                } catch (javafl.JavaAfl.SurrogateExitSecurityException e) {
                    continue;
                } catch (Throwable e) {
                    running = javafl.JavaAfl.handle_exception(e);
                }
            } catch (ThreadDeath e) {
                running = javafl.JavaAfl.handle_exception(e);
            }
        }
        javafl.JavaAfl._after_main();
    }
    public static void main(String[] args)
    {
        switch (args[0]) {
        case "ok":
            execute(new Runnable() { public void run() { } });
            break;
        case "exit":
            execute(new Runnable() { public void run() { System.exit(1); }});
            break;
        case "exception":
            execute(new Runnable() { public void run() {
                throw new RuntimeException(); }});
            break;
        case "persistent":
            execute(new Runnable() { public void run() {
                while (javafl.fuzz.loop()) {}
            }});
            break;
        case "deferred":
            execute(new Runnable() { public void run() {
                javafl.fuzz.init();
            }});
            break;
        case "deferred-persistent":
            execute(new Runnable() { public void run() {
                javafl.fuzz.init();
                while (javafl.fuzz.loop()) {}
            }});
            break;
        default:
            throw new RuntimeException("Unknown argument: " + args[0]);
        }
    }
}
