#include <errno.h>
#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <sys/shm.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <sys/wait.h>

#include <JavaAfl.h>

// These constants must be kept in sync with afl-fuzz:
#ifndef MAP_SIZE
#define MAP_SIZE_POW2 16;
static const size_t MAP_SIZE = 1 << MAP_SIZE_POW2;
#endif
static const char SHM_ENV_VAR[] = "__AFL_SHM_ID";
static const int FORKSRV_FD = 198;

// These are global helper variables to avoid discovering the same
// information again and again.
static void* g_afl_area = (void*)-1;
static void* g_zero_area = NULL;
static jfieldID g_map_field_id = NULL;
static jobject g_map_field = NULL;
static bool g_is_persistent = false;

static void init_map_field(JNIEnv *env, jclass cls)
{
    jfieldID map_field_id = (*env)->GetStaticFieldID(env, cls, "map", "[B");
    if (map_field_id == NULL) {
        fprintf(stderr, "No AflFuzz.map field found from Java class!\n");
        abort();
    }
    g_map_field_id = map_field_id;
}

static jobject get_map_field(JNIEnv *env, jclass cls)
{
    return (*env)->GetStaticObjectField(env, cls, g_map_field_id);
}

JNIEXPORT jint JNICALL Java_JavaAfl__1get_1map_1size
  (JNIEnv * env, jclass cls)
{
    return MAP_SIZE;
}

JNIEXPORT void JNICALL Java_JavaAfl__1init_1impl
  (JNIEnv * env, jclass cls, jboolean is_persistent)
{
    static bool initialized = false;
    if (initialized) {
        fprintf(stderr, "Tried to initialize java-afl twice!");
        abort();
    }

    bool use_forkserver = true;
    {
        int result = write(FORKSRV_FD + 1, "\x00\x00\x00\x00", 4);
        if (result == -1) {
            if (errno == EBADF) {
                use_forkserver = false;
            } else {
                perror("Failed to send data to fork server");
                abort();
            }
        }
    }

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

    g_is_persistent = is_persistent;
    initialized = true;

    const char* afl_shm_id = getenv(SHM_ENV_VAR);
    if (afl_shm_id == NULL) {
        return;
    }

    // This area of zeros is here only to be able to zero the map
    // memory on Java side fast when in persistent fuzzing mode.
    g_zero_area = calloc(1, MAP_SIZE);

    g_afl_area = shmat(atoi(afl_shm_id), NULL, 0);
    if (g_afl_area == (void*)-1) {
        perror("No shared memory area!");
        abort();
    }
    init_map_field(env, cls);
    // It's possible that Java side instrumentation has already
    // written something to the map. Reset it so that especially
    // persistent mode gets a clean slate to start. Deferred mode
    // also should benefit from the map being less full in the
    // beginning.
    (*env)->SetByteArrayRegion(
        env, get_map_field(env, cls), 0, MAP_SIZE, g_zero_area);
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

JNIEXPORT void JNICALL Java_JavaAfl__1after_1main
  (JNIEnv * env, jclass cls)
{
    // In persistent mode JavaAfl.loop() does the final map update for
    // us. Doing map updates after the main loop leads into
    // instability, as there are map updates in the code after that.
    // TODO rethink this approach, as there is quite a lot of ifs
    // taking care of the persistent mode.
    if (!g_is_persistent) {
        send_map(env, cls);
    }
    // TODO this should not be needed here, but something in the
    // fork server prevents this from existing cleanly.
    _Exit(0);
}

JNIEXPORT void JNICALL Java_JavaAfl__1handle_1uncaught_1exception
  (JNIEnv * env, jclass cls)
{
    send_map(env, cls);
    kill(getpid(), SIGUSR1);
}

JNIEXPORT void JNICALL Java_JavaAfl__1send_1map
  (JNIEnv * env, jclass cls)
{
    send_map(env, cls);
    kill(getpid(), SIGSTOP);
}
