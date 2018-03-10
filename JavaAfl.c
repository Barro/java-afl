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
static jobject g_map_field = NULL;

static void init_map_field(JNIEnv *env, jclass cls)
{
    jfieldID map_field_id = (*env)->GetStaticFieldID(env, cls, "map", "[B");
    if (map_field_id == NULL) {
        printf("No map field found!\n");
        return;
    }
    g_map_field =(*env)->GetStaticObjectField(env, cls, map_field_id);
    if (g_map_field == NULL) {
        printf("Could not get object field!\n");
        return;
    }
}

JNIEXPORT void JNICALL Java_JavaAfl__1before_1main
(JNIEnv *env, jclass cls, jboolean is_persistent)
{
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

    const char* afl_shm_id = getenv(SHM_ENV_VAR);
    if (afl_shm_id == NULL) {
        return;
    }

    g_afl_area = shmat(atoi(afl_shm_id), NULL, 0);
    if (g_afl_area == (void*)-1) {
        perror("No shared memory area!");
        abort();
    }
}

JNIEXPORT void JNICALL Java_JavaAfl__1after_1main
  (JNIEnv * env, jclass cls)
{
    init_map_field(env, cls);
    if (g_map_field == NULL) {
        return;
    }
    /* jbyte* g_map_data = (*env)->GetByteArrayElements(env, g_map_field, NULL); */
    jbyte* map_data;
    /* if (g_map_data == NULL) { */
    /*     return; */
    /* } */
    if (g_afl_area != (void*)-1) {
        (*env)->GetByteArrayRegion(env, g_map_field, 0, MAP_SIZE, g_afl_area);
        //memcpy(g_afl_area, g_map_data, MAP_SIZE);
    }
    // This should not be needed here, but something in the forkserver
    // prevents this from existing cleanly.
    _Exit(0);
}

JNIEXPORT void JNICALL Java_JavaAfl__1handle_1uncaught_1exception
  (JNIEnv * env, jclass cls)
{
    kill(getpid(), SIGUSR1);
}
