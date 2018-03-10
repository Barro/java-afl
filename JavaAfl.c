#include "Tst.h"
#include <errno.h>
#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <sys/shm.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <sys/wait.h>

// These constants must be kept in sync with afl-fuzz:
const char SHM_ENV_VAR[] = "__AFL_SHM_ID";
const int FORKSRV_FD = 198;
#define MAP_SIZE_POW2 16;
const size_t MAP_SIZE = 1 << MAP_SIZE_POW2;

static void* g_afl_area = (void*)-1;
static void* g_zero_area = NULL;
static jfieldID g_map_field_id = NULL;
static jobject g_map_field = NULL;
static bool g_is_persistent = false;

static void init_map_field(JNIEnv *env, jclass cls)
{
    jfieldID map_field_id = (*env)->GetStaticFieldID(env, cls, "map", "[B");
    if (map_field_id == NULL) {
        printf("No map field found!\n");
        return;
    }
    g_map_field_id = map_field_id;
}

static jobject get_map_field(JNIEnv *env, jclass cls)
{
    return (*env)->GetStaticObjectField(env, cls, g_map_field_id);
    /* if (g_map_field == NULL) { */
    /*     printf("Could not get object field!\n"); */
    /*     return; */
    /* } */
}

JNIEXPORT void JNICALL Java_JavaAfl__1init_1impl
  (JNIEnv * env, jclass cls, jboolean is_persistent)
{
    static bool initialized = false;
    if (initialized) {
        abort();
    }

    //printf("Starting\n");
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
        if (read(FORKSRV_FD, child_killed_data.child_killed_buf, 4) != 4) {
            perror("Failed to read child killed data");
            abort();
        }
        if (child_stopped && child_killed_data.child_killed) {
            waitpid(child_pid_data.child_pid, NULL, 0);
            child_stopped = false;
        }
        if (child_stopped) {
            kill(child_pid_data.child_pid, SIGCONT);
            child_stopped = false;
        } else {
            child_pid_data.child_pid = fork();
            if (!child_pid_data.child_pid) {
                // child
                break;
            }
        }
        // parent
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

    g_afl_area = shmat(atoi(afl_shm_id), NULL, 0);
    if (g_afl_area == (void*)-1) {
        perror("No shared memory area!");
        abort();
    }
    g_zero_area = calloc(1, MAP_SIZE);
    init_map_field(env, cls);
    printf("START %p %p %p\n", g_afl_area, env, g_map_field);
    (*env)->SetByteArrayRegion(
        env, get_map_field(env, cls), 0, MAP_SIZE, g_zero_area);
}

static void send_map(JNIEnv * env, jclass cls)
{
    /* jbyte* g_map_data = (*env)->GetByteArrayElements(env, g_map_field, NULL); */
    /* jbyte* map_data; */
    /* if (g_map_data == NULL) { */
    /*     return; */
    /* } */
    if (g_afl_area != (void*)-1) {
        jobject map_field = get_map_field(env, cls);
        (*env)->GetByteArrayRegion(env, map_field, 0, MAP_SIZE, g_afl_area);
        (*env)->SetByteArrayRegion(env, map_field, 0, MAP_SIZE, g_zero_area);
        //memcpy(g_afl_area, g_map_data, MAP_SIZE);
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
    // This should not be needed here, but something in the forkserver
    // prevents this from existing cleanly.
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
