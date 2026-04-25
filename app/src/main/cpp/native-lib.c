#include <string.h>
#include <jni.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>
#include <android/log.h>

#include "main.h"

#define LOG_TAG "ByeDpiNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern int server_fd;
extern int optind;

static volatile int g_proxy_running = 0;

JNIEXPORT jint JNICALL
Java_com_example_youoffline_nativebridge_ByeDpiProxy_jniStartProxy(
        JNIEnv *env, __attribute__((unused)) jobject thiz, jobjectArray args) {

    if (g_proxy_running) {
        LOGD("proxy already running");
        return -1;
    }

    jsize argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!argv) {
        LOGD("failed to allocate argv");
        return -1;
    }

    for (jsize i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        if (!arg) { argv[i] = NULL; continue; }
        const char *cs = (*env)->GetStringUTFChars(env, arg, NULL);
        argv[i] = cs ? strdup(cs) : NULL;
        if (cs) (*env)->ReleaseStringUTFChars(env, arg, cs);
        (*env)->DeleteLocalRef(env, arg);
    }
    argv[argc] = NULL;

    LOGD("starting proxy with %d args", (int)argc);
    clear_params(NULL, NULL);
    g_proxy_running = 1;
    /* reset getopt for repeated calls */
    optind = 1;
    int result = main((int)argc, argv);
    LOGD("proxy exited with code %d", result);
    g_proxy_running = 0;
    server_fd = -1;

    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_example_youoffline_nativebridge_ByeDpiProxy_jniStopProxy(
        __attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {

    LOGD("stopping proxy (fd: %d)", server_fd);
    if (!g_proxy_running || server_fd < 0) {
        LOGD("proxy not running");
        return -1;
    }
    int rc = shutdown(server_fd, SHUT_RDWR);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_example_youoffline_nativebridge_ByeDpiProxy_jniForceClose(
        __attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {

    LOGD("force closing proxy socket (fd: %d)", server_fd);
    if (server_fd < 0) {
        return -1;
    }
    int rc = close(server_fd);
    g_proxy_running = 0;
    server_fd = -1;
    return rc;
}
