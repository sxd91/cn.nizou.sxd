// signprobe_native.cpp — sign 逆向 native 探针（方案 A）
// 用 shadowhook 挂 libRequestEncoder.so + 0x60810（getEncodedP 的 native 实现），
// 落盘 (s1, s2, i) -> sign 完整映射。
#include <jni.h>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#include <mutex>
#include <string>
#include <ctime>
#include <android/log.h>
#include "shadowhook.h"

#define TAG "SignProbeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// getEncodedP 的 JNI 实现签名：(JNIEnv*, jclass, jstring, jstring, jint) -> jstring
static jstring (*orig_getEncodedP)(JNIEnv*, jclass, jstring, jstring, jint) = nullptr;
static std::mutex g_mutex;
static std::string g_log_path;

static void native_log(const char* fmt, ...) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_log_path.empty()) return;
    FILE* f = fopen(g_log_path.c_str(), "a");
    if (!f) return;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tmv;
    localtime_r(&ts.tv_sec, &tmv);
    char tbuf[32];
    strftime(tbuf, sizeof(tbuf), "%H:%M:%S", &tmv);
    fprintf(f, "%s.%03ld NATIVE ", tbuf, ts.tv_nsec / 1000000);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fprintf(f, "\n");
    fclose(f);
}

static jstring my_getEncodedP(JNIEnv* env, jclass clazz, jstring s1, jstring s2, jint i) {
    jstring ret = orig_getEncodedP(env, clazz, s1, s2, i);
    const char* c1 = s1 ? env->GetStringUTFChars(s1, nullptr) : "(null)";
    const char* c2 = s2 ? env->GetStringUTFChars(s2, nullptr) : "(null)";
    const char* cr = ret ? env->GetStringUTFChars(ret, nullptr) : "(null)";
    native_log("getEncodedP(s1=%s, s2=%s, i=%d) -> %s", c1, c2, i, cr);
    if (s1) env->ReleaseStringUTFChars(s1, c1);
    if (s2) env->ReleaseStringUTFChars(s2, c2);
    if (ret) env->ReleaseStringUTFChars(ret, cr);
    return ret;
}

// /proc/self/maps 里 libRequestEncoder.so 的最低映射地址
static uintptr_t find_so_base() {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "libRequestEncoder.so")) {
            uintptr_t lo = strtoull(line, nullptr, 16);
            if (base == 0 || lo < base) base = lo;
        }
    }
    fclose(f);
    return base;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_nizou_sxd_util_SignProbeNative_installHook(JNIEnv* env, jclass, jstring jLogPath, jint offset) {
    const char* lp = env->GetStringUTFChars(jLogPath, nullptr);
    g_log_path = lp;
    env->ReleaseStringUTFChars(jLogPath, lp);

    uintptr_t base = find_so_base();
    if (base == 0) {
        native_log("installHook: libRequestEncoder.so not in maps");
        LOGE("libRequestEncoder.so not in maps");
        return -1;
    }
    native_log("installHook: base=0x%lx offset=0x%x", (unsigned long)base, offset);
    LOGI("so base = 0x%lx", (unsigned long)base);

    if (shadowhook_init(SHADOWHOOK_MODE_SHARED, false) != 0) {
        native_log("shadowhook_init failed");
        LOGE("shadowhook_init failed");
        return -2;
    }
    void* target = reinterpret_cast<void*>(base + offset);
    void* orig = nullptr;
    void* res = shadowhook_hook_func_addr(target, (void*)my_getEncodedP, &orig);
    if (!res) {
        native_log("hook failed: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
        LOGE("hook failed");
        return -3;
    }
    orig_getEncodedP = (jstring (*)(JNIEnv*, jclass, jstring, jstring, jint))orig;
    native_log("hooked getEncodedP OK");
    LOGI("hooked getEncodedP at base+0x%x", offset);
    return 0;
}