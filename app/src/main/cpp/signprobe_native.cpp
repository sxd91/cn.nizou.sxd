// signprobe_native.cpp — sign 逆向 native 探针 v2
// v1 结论：hook 报成功但 21:52 的 sign 一条未捕获；base+0x60810 落在 rwxp 段，
// vgo 有运行时自修改/解密代码区，疑似抹掉跳板或 fnPtr 指向解密副本。
// v2 策略：入口字节自检 + 自动重挂 + 双地址对照（0x60810 与 0x61bf4）+ 定期 health check。
#include <jni.h>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#include <cstdint>
#include <mutex>
#include <string>
#include <ctime>
#include <unistd.h>
#include <android/log.h>
#include "shadowhook.h"

#define TAG "SignProbeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static jstring (*orig_g1)(JNIEnv*, jclass, jstring, jstring, jint) = nullptr;
static jstring (*orig_g2)(JNIEnv*, jclass, jstring, jstring, jint) = nullptr;
static std::mutex g_mutex;
static std::string g_log_path;
static uintptr_t g_base = 0;

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

static void dump_args(JNIEnv* env, jclass clazz, jstring s1, jstring s2, jint i, jstring ret,
                      const char* tag) {
    const char* c1 = s1 ? env->GetStringUTFChars(s1, nullptr) : "(null)";
    const char* c2 = s2 ? env->GetStringUTFChars(s2, nullptr) : "(null)";
    const char* cr = ret ? env->GetStringUTFChars(ret, nullptr) : "(null)";
    native_log("HIT[%s] getEncodedP(s1=%s, s2=%s, i=%d) -> %s", tag, c1, c2, i, cr);
    if (s1) env->ReleaseStringUTFChars(s1, c1);
    if (s2) env->ReleaseStringUTFChars(s2, c2);
    if (ret) env->ReleaseStringUTFChars(ret, cr);
}

static jstring my_g1(JNIEnv* env, jclass c, jstring s1, jstring s2, jint i) {
    jstring r = orig_g1(env, c, s1, s2, i);
    dump_args(env, c, s1, s2, i, r, "P1");
    return r;
}
static jstring my_g2(JNIEnv* env, jclass c, jstring s1, jstring s2, jint i) {
    jstring r = orig_g2(env, c, s1, s2, i);
    dump_args(env, c, s1, s2, i, r, "P2");
    return r;
}

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

// 读自身进程内存（探针就跑在宿主进程里，ptrace-free）
static bool read_mem(uintptr_t addr, void* buf, size_t n) {
    FILE* f = fopen("/proc/self/mem", "r");
    if (!f) return false;
    bool ok = fseek(f, (long)addr, SEEK_SET) == 0 && fread(buf, 1, n, f) == n;
    fclose(f);
    return ok;
}

static bool hook_at(uintptr_t addr, const char* tag, void* repl, void** orig) {
    unsigned char before[16], after[16];
    read_mem(addr, before, 16);
    void* res = shadowhook_hook_func_addr((void*)addr, repl, orig);
    if (!res) {
        native_log("hook[%s] @0x%lx FAILED: %s", tag, (unsigned long)addr,
                   shadowhook_to_errmsg(shadowhook_get_errno()));
        return false;
    }
    read_mem(addr, after, 16);
    char hb[49], ha[49];
    for (int i = 0; i < 16; i++) { sprintf(hb + i * 3, "%02x ", before[i]); sprintf(ha + i * 3, "%02x ", after[i]); }
    hb[47] = ha[47] = 0;
    native_log("hook[%s] @0x%lx OK before=[%s] after=[%s]", tag, (unsigned long)addr, hb, ha);
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_nizou_sxd_util_SignProbeNative_installHook(JNIEnv* env, jclass, jstring jLogPath) {
    const char* lp = env->GetStringUTFChars(jLogPath, nullptr);
    g_log_path = lp;
    env->ReleaseStringUTFChars(jLogPath, lp);

    g_base = find_so_base();
    if (g_base == 0) { native_log("installHook: so not in maps"); return -1; }

    static bool inited = false;
    if (!inited) { shadowhook_init(SHADOWHOOK_MODE_SHARED, false); inited = true; }

    // P1: getEncodedP 静态实现地址；P2: 同链另一方法（机制对照）
    bool ok1 = hook_at(g_base + 0x60810, "P1", (void*)my_g1, (void**)&orig_g1);
    bool ok2 = hook_at(g_base + 0x61bf4, "P2", (void*)my_g2, (void**)&orig_g2);
    native_log("installHook: base=0x%lx P1=%s P2=%s", (unsigned long)g_base, ok1?"OK":"FAIL", ok2?"OK":"FAIL");
    return (ok1 || ok2) ? 0 : -3;
}