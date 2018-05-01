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

#include <assert.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <sys/shm.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <sys/wait.h>

#include <javafl_JavaAfl.h>

// Use afl's config.h for constants.
#ifdef HAVE_AFL_CONFIG_H

#include <config.h>

#else // #ifndef HAVE_AFL_CONFIG_H

#ifdef HAVE_AFL_MAP_SIZE_H

#include <afl-map-size.h>

#endif // #ifdef HAVE_AFL_MAP_SIZE_H

// These constants must be kept in sync with afl-fuzz:
#ifndef MAP_SIZE

#ifndef MAP_SIZE_POW2
#define MAP_SIZE_POW2 16;
#endif // #ifndef MAP_SIZE_POW2

static const size_t MAP_SIZE = 1 << MAP_SIZE_POW2;
#endif // #ifndef MAP_SIZE

static const char SHM_ENV_VAR[] = "__AFL_SHM_ID";
static const int FORKSRV_FD = 198;

#endif // #ifndef HAVE_AFL_CONFIG_H

// These are used to communicate back the environment to Java side at
// init:
static const jint INIT_HAS_SHM = 1 << 0;
static const jint INIT_HAS_FORKSERVER = 1 << 1;

// These are global helper variables to avoid discovering the same
// information again and again.
static void* g_afl_area = (void*)-1;
static void* g_zero_area = NULL;
static jfieldID g_map_field_id = NULL;
static bool g_initialized = false;

// Indicate an appropriate mode:
static bool g_is_surrogate_mode = false;
static bool g_is_persistent_mode = false;

// This is something that a persistent fork server can send back to
// afl-fuzz when an uncaught exception happens:
static int g_aborted_wstatus;
// Send this back to afl-fuzz on a regular result.
static int g_stopped_wstatus;
// Send this back to afl-fuzz on a regular result.
static int g_killed_wstatus;

union {
    char pid_buf[4];
    pid_t pid;
} g_fork_surrogate_info = { .pid = 0 };

static void init_map_field(JNIEnv *env, jclass cls)
{
    // In "map" field can actually change its value in middle of the
    // execution from a byte array to null when we want to be somewhat
    // sure that the data processing thread results in an uncaught
    // exception. Therefore only read data from the array that is
    // allocated just once in the beginning.
    jfieldID map_field_id = (*env)->GetStaticFieldID(env, cls, "_map_original", "[B");
    if (map_field_id == NULL) {
        fprintf(stderr, "No _map_original field found from JavaAfl class!\n");
        abort();
    }
    g_map_field_id = map_field_id;
}

static jint get_prev_location(JNIEnv *env, jclass cls)
{
    jfieldID prev_location_field_id = (*env)->GetStaticFieldID(env, cls, "prev_location", "I");
    return (*env)->GetStaticIntField(env, cls, prev_location_field_id);
}

static jobject get_map_field(JNIEnv *env, jclass cls)
{
    return (*env)->GetStaticObjectField(env, cls, g_map_field_id);
}

/**
 * Copies map data generated in Java side to the shared memory and at
 * the same time zeroes it.
 */
static void send_map(JNIEnv * env, jclass cls)
{
    if (g_afl_area != (void*)-1) {
        jobject map_field = get_map_field(env, cls);
        (*env)->GetByteArrayRegion(env, map_field, 0, MAP_SIZE, g_afl_area);
        (*env)->SetByteArrayRegion(env, map_field, 0, MAP_SIZE, g_zero_area);
    }
}

static void write_debug(const char* message)
{
    FILE* debug = fopen("/tmp/debug.txt", "a+");
    fprintf(debug, "%s\n", message);
    fclose(debug);
}

static int get_aborted_wstatus(void)
{
    pid_t child_pid = fork();
    if (child_pid == -1) {
        perror("Unable to fork a process!\n");
        abort();
    }
    if (child_pid == 0) {
        abort();
    }
    int wstatus;
    pid_t changed_child = waitpid(child_pid, &wstatus, 0);
    if (changed_child == -1) {
        perror("waitpid() returned an error on just spawned child!");
        abort();
    }
    assert(changed_child == child_pid);
    return wstatus;
}

static int get_stopped_wstatus(void)
{
    pid_t child_pid = fork();
    if (child_pid == -1) {
        perror("Unable to fork a process!\n");
        abort();
    }
    if (child_pid == 0) {
        while (true) {
            sleep(1000);
        }
    }
    int wstatus;
    kill(child_pid, SIGSTOP);
    int options = WUNTRACED;
    pid_t stopped_child = waitpid(child_pid, &wstatus, options);
    if (stopped_child == -1) {
        perror("waitpid() returned an error on just spawned child!");
        abort();
    }
    assert(stopped_child == child_pid);

    kill(child_pid, SIGKILL);
    pid_t killed_child = waitpid(child_pid, NULL, 0);

    assert(killed_child != -1);
    assert(killed_child == child_pid);

    return wstatus;
}

static int get_killed_wstatus(void)
{
    pid_t child_pid = fork();
    if (child_pid == -1) {
        perror("Unable to fork a process!\n");
        abort();
    }
    if (child_pid == 0) {
        while (true) {
            sleep(1000);
        }
    }
    int wstatus;
    kill(child_pid, SIGKILL);
    pid_t killed_child = waitpid(child_pid, &wstatus, 0);
    if (killed_child == -1) {
        perror("waitpid() returned an error on just spawned child!");
        abort();
    }
    assert(killed_child == child_pid);

    return wstatus;
}

static bool try_init_forkserver(void)
{
    write_debug("JAVA");
    int result = write(FORKSRV_FD + 1, "JAVA", 4);
    if (result == -1) {
        if (errno == EBADF) {
            return false;
        } else {
            perror("Failed to send data to fork server");
            abort();
        }
    }
    return true;
}

static bool fork_shm_freeing_parent_monitor(int shm_id)
{
    pid_t parent_pid = getpid();
    pid_t child = fork();
    if (child == -1) {
        fprintf(stderr, "Unable to fork a monitoring child!");
        return false;
    }
    if (child != 0) {
        return true;
    }
    while (true) {
        // If parent dies, getppid() gets different value than in the
        // start-up. We can then start doing shared memory cleanup
        // operations.
        if (getppid() != parent_pid) {
            break;
        }
        sleep(1);
    }
    // Mark the shared memory area for destruction.
    shmctl(shm_id, IPC_RMID, NULL);
    // TODO are killed processes detaching the shared memory by just dying?
    // Just exit here to prevent the forked Java process from running
    // further or running any .
    _Exit(0);
}

static bool try_init_shared_memory(
    JNIEnv* env, jclass cls, jlong class_id)
{
    if (g_initialized) {
        fprintf(
            stderr,
            "Tried to initialize java-afl shared memory twice! "
            "This should not happen.\n"
            );
        abort();
    }

    // Check if we have information about shared memory area passed to
    // this process:
    const char* afl_shm_id = getenv(SHM_ENV_VAR);
    if (afl_shm_id == NULL) {
        return false;
    }
    g_afl_area = shmat(atoi(afl_shm_id), NULL, 0);
    if (g_afl_area == (void*)-1) {
        perror("We have been lied to! No shared memory area at its ID!");
        abort();
    }

    // Always have something non-zero to send back to afl-fuzz.
    g_zero_area = calloc(1, MAP_SIZE);
    memcpy(g_zero_area, g_afl_area, MAP_SIZE);
    ((char*)g_zero_area)[class_id % MAP_SIZE]++;

    init_map_field(env, cls);

    return true;
}
/*
JNIEXPORT jobject JNICALL Java_javafl_JavaAfl__1get_1map_1memory_1area(
    JNIEnv* env, jclass cls)
{
    write_debug("Get map memory area");
    return init_shared_memory(env, cls);
}
*/
JNIEXPORT jint JNICALL Java_javafl_JavaAfl__1get_1map_1size
  (JNIEnv * env, jclass cls)
{
    return MAP_SIZE;
}

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1monitor_1java(
    JNIEnv * env, jclass cls, jlong error_code)
{
    {
        FILE* debug = fopen("/tmp/debug.txt", "a+");
        fprintf(debug, "monitor %ld\n", error_code);
        fclose(debug);
    }

}

static bool was_prev_child_timedout(void)
{
    union {
        char child_timedout_buf[4];
        uint32_t child_timedout;
    } child_timedout_data;
    if (read(FORKSRV_FD, child_timedout_data.child_timedout_buf, 4) != 4) {
        perror("Failed to read child timedout data");
        abort();
    }
    return !!child_timedout_data.child_timedout;
}

JNIEXPORT jint JNICALL Java_javafl_JavaAfl__1wait_1for_1fork_1surrogate
(JNIEnv * env, jclass cls, jlong fork_surrogate_pid)
{
    assert(fork_surrogate_pid != 0 && "Zero fork surrogate pid should not happen!");
    int wstatus;
    FILE* debug = fopen("/tmp/debug-wait.txt", "a+");
    fprintf(debug, "waiting for pid %ld\n", fork_surrogate_pid);
    fclose(debug);
    waitpid(fork_surrogate_pid, &wstatus, WUNTRACED);
    {
        FILE* debug = fopen("/tmp/debug-wait.txt", "a+");
        fprintf(debug, "help! timedout!\n");
        fclose(debug);
    }
    return wstatus;
}

JNIEXPORT jlong JNICALL Java_javafl_JavaAfl__1new_1fork_1surrogate
  (JNIEnv * env, jclass cls)
{
    bool timedout = was_prev_child_timedout();
    pid_t child = fork();
    if (child == -1) {
        fprintf(stderr, "Unable to fork a child!");
        abort();
    }
    // We are the child. Do nothing and sleep until killed. This child
    // process is just a surrogate for a real child process in a
    // persistent mode that a fork server based approach should use.
    if (child == 0) {
        while (true) {
            sleep(1 << 15);
        }
    }
    g_fork_surrogate_info.pid = child;

    {
        FILE* debug = fopen("/tmp/debug.txt", "a+");
        fprintf(debug, "new fork surrogate %d\n", g_fork_surrogate_info.pid);
        fclose(debug);
    }
    // Communicate back to the fork server that we have a new child!
    write_debug("surrogate pid");
    write(FORKSRV_FD + 1, g_fork_surrogate_info.pid_buf, 4);
    // Parent returns the child pid for the actual program to handle.
    return child;
}

static bool g_caught_child_timedout = false;

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1send_1child_1ok_1status(
    JNIEnv* env, jclass cls)
{
    /*
    if (was_child_timedout()) {
        // We are in a race condition where the child has timed out,
        // but we haven't been killed yet.
        g_caught_child_timedout = true;
        return false;
        }*/
    /* ((char*)g_afl_area)[g_start_location]++; */
    send_map(env, cls);
    // Forkserver mode
    // Forkserver mode in persistent loop -> SIGSTOP
    // Surrogate mode -> 
    // kill(getpid(), SIGSTOP);
    /* write_debug("wstatus"); */
    if (g_is_surrogate_mode) {
        write(FORKSRV_FD + 1, &g_stopped_wstatus, sizeof(g_stopped_wstatus));
        bool timedout = was_prev_child_timedout();
        write(FORKSRV_FD + 1, g_fork_surrogate_info.pid_buf, 4);
    } else if (g_is_persistent_mode) {
        kill(getpid(), SIGSTOP);
    }
}

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1send_1child_1killed_1status(
    JNIEnv * env, jclass cls, jint wstatus_java)
{
    assert(g_is_surrogate_mode && "Impossible function call from a zombie process");
    int wstatus = (int)wstatus_java;
    /*
    if (g_caught_child_timedout) {
        g_caught_child_timedout = false;
    } else {
        if (!was_child_timedout()) {
            fprintf(stderr, "We should have been timed out but we aren't!");
            abort();
        }
    }*/
    send_map(env, cls);
    write(FORKSRV_FD + 1, &wstatus, sizeof(wstatus));
}

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1send_1uncaught_1exception_1status(
    JNIEnv* env, jclass cls)
{
    //bool timedout = was_child_timedout();
    /*
    if (was_child_timedout()) {
        // We are in a race condition where the child has timed out,
        // but we haven't been killed yet.
        g_caught_child_timedout = true;
        return false;
    }
    */
    send_map(env, cls);
    if (g_is_surrogate_mode) {
        write(FORKSRV_FD + 1, &g_aborted_wstatus, sizeof(g_aborted_wstatus));
        bool timedout = was_prev_child_timedout();
        write(FORKSRV_FD + 1, g_fork_surrogate_info.pid_buf, 4);
    } else {
        abort();
    }
}

JNIEXPORT jint JNICALL Java_javafl_JavaAfl__1jni_1init(
    JNIEnv * env, jclass cls, jlong class_id)
{
    if (g_initialized) {
        fprintf(
            stderr,
            "Tried to initialize java-afl twice! This should not happen.");
        abort();
    }

    jint init_flags = 0;

    if (try_init_shared_memory(env, cls, class_id)) {
        // This basically sets the non-zero shared memory to the
        // program's byte array map.
        send_map(env, cls);
        init_flags |= INIT_HAS_SHM;
    } else {
        // No point in continuing if there is no shared memory, as we
        // can not communicate any findings further.
        return init_flags;
    }

    if (try_init_forkserver()) {
        init_flags |= INIT_HAS_FORKSERVER;
    }

    g_initialized = true;
    return init_flags;
/*
    bool child_stopped = false;
    union
    {
        char child_pid_buf[4];
        pid_t child_pid;
    } child_pid_data = { .child_pid = 0 };
    while (use_forkserver) {
        union
        {
            char child_killed_buf[4];
            unsigned child_killed;
        } child_killed_data;
        // Wait for parent. It can also tell us that it has killed the
        // child process::
        if (read(FORKSRV_FD, child_killed_data.child_killed_buf, 4) != 4) {
            perror("Failed to read child killed data");
            abort();
        }
        // This handles the race condition where the child receives
        // SIGSTOP first in the persistent mode and then is killed by
        // the parent for timing out.
        if (child_stopped && child_killed_data.child_killed) {
            child_stopped = false;
            if (waitpid(child_pid_data.child_pid, NULL, 0) == -1) {
                perror("Waiting for the child process failed!");
                abort();
            }
        }
        if (child_stopped) {
            // In persistent mode the child will send SIGSTOP to
            // itself after it has written map data to the shared
            // memory. This makes it run for another round in
            // persistent mode.
            kill(child_pid_data.child_pid, SIGCONT);
            child_stopped = false;
        } else {
            child_pid_data.child_pid = fork();
            if (!child_pid_data.child_pid) {
                // Child will directly jump to shared memory handling
                // related code.
                break;
            }
        }
        // Parent will repeatedly write status information back to
        // afl-fuzz process.
        write(FORKSRV_FD + 1, child_pid_data.child_pid_buf, 4);
        int wstatus;
        int options = 0;
        if (is_persistent) {
            options = WUNTRACED;
        }
        waitpid(child_pid_data.child_pid, &wstatus, options);
        child_stopped = WIFSTOPPED(wstatus);
        write(FORKSRV_FD + 1, &wstatus, sizeof(wstatus));
    }
    if (use_forkserver) {
        close(FORKSRV_FD);
        close(FORKSRV_FD + 1);
    }

    g_is_persistent_mode = is_persistent;
    init_shared_memory(env, cls);
    g_initialized = true;
*/
}

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1jni_1init_1forkserver_1mode(
    JNIEnv* env, jclass cls)
{
}

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1jni_1init_1surrogate_1mode(
    JNIEnv* env, jclass cls)
{
    // Figure out what to send back to the afl-fuzz when something
    // nasty happens.
    g_aborted_wstatus = get_aborted_wstatus();
    g_stopped_wstatus = get_stopped_wstatus();
    g_killed_wstatus = get_killed_wstatus();

}

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1force_1exit_1forked_1child(
    JNIEnv* env, jclass cls, jint exit_status)
{
    // JVM waits for specific threads to finish that do not exist
    // anymore in the forked child processes. This exits from the
    // program before any JVM side exit handlers get to run.
    _Exit(exit_status);
}

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1handle_1uncaught_1exception
  (JNIEnv * env, jclass cls)
{
    send_map(env, cls);
    abort();
}

JNIEXPORT void JNICALL Java_javafl_JavaAfl__1send_1map
  (JNIEnv * env, jclass cls)
{
    send_map(env, cls);
    kill(getpid(), SIGSTOP);
}
