// signprobe_native.cpp — sign 逆向 native 探针 v3
// 基于静态逆向结论优化：
//   0x64990 = MD5-reset+update(ctx, std::string*)  ← sign 生成必经之路，捕获每段 MD5 输入
//   0x61bf4 = getEncodedP(path, salt, ts)          ← 抓三元组
// 只读，零行为改变。
#include <jni.h>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#include <cstdint>
#include <mutex>
#include <string>
#include <ctime>
#include <android/log.h>
#include "shadowhook.h"

#define TAG "SignProbeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

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

// libc++ std::string 读取（short/long 两种布局）
static void read_libcpp_string(void* sp, char* out, size_t outsz, size_t* outlen) {
    unsigned char* p = (unsigned char*)sp;
    unsigned char b0 = p[0];
    const char* data; size_t len;
    if (b0 & 1) {                       // long string
        data = *(const char**)(p + 16);
        len = *(size_t*)(p + 8);
    } else {                            // short string
        data = (const char*)(p + 1);
        len = b0 >> 1;
    }
    if (len > outsz - 1) len = outsz - 1;
    memcpy(out, data, len);
    out[len] = 0;
    if (outlen) *outlen = len;
}

// ---------- hook: 0x64990 MD5-reset+update(ctx, string) ----------
static void (*orig_md5upd)(void*, void*) = nullptr;
static void my_md5upd(void* ctx, void* sp) {
    char buf[4096];
    size_t len = 0;
    read_libcpp_string(sp, buf, sizeof(buf), &len);
    native_log("MD5UPD[%zu] %s", len, buf);
    orig_md5upd(ctx, sp);
}

// ---------- hook: 0x61bf4 getEncodedP(env, clazz, s1, s2, i) ----------
static jstring (*orig_getEncodedP)(JNIEnv*, jclass, jstring, jstring, jint) = nullptr;
static jstring my_getEncodedP(JNIEnv* env, jclass c, jstring s1, jstring s2, jint i) {
    jstring r = orig_getEncodedP(env, c, s1, s2, i);
    const char* a = s1 ? env->GetStringUTFChars(s1, nullptr) : "(null)";
    const char* b = s2 ? env->GetStringUTFChars(s2, nullptr) : "(null)";
    const char* rr = r ? env->GetStringUTFChars(r, nullptr) : "(null)";
    native_log("GETENC(s1=%s, s2=%s, i=%d) -> %s", a, b, i, rr);
    if (s1) env->ReleaseStringUTFChars(s1, a);
    if (s2) env->ReleaseStringUTFChars(s2, b);
    if (r) env->ReleaseStringUTFChars(r, rr);
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

static bool hook_at(uintptr_t addr, const char* tag, void* repl, void** orig) {
    void* res = shadowhook_hook_func_addr((void*)addr, repl, orig);
    if (!res) {
        native_log("hook[%s] @0x%lx FAILED: %s", tag, (unsigned long)addr,
                   shadowhook_to_errmsg(shadowhook_get_errno()));
        return false;
    }
    native_log("hook[%s] @0x%lx OK", tag, (unsigned long)addr);
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_nizou_sxd_util_SignProbeNative_installHook(JNIEnv* env, jclass, jstring jLogPath) {
    const char* lp = env->GetStringUTFChars(jLogPath, nullptr);
    g_log_path = lp;
    env->ReleaseStringUTFChars(jLogPath, lp);

    uintptr_t base = find_so_base();
    if (base == 0) { native_log("installHook: so not in maps"); return -1; }

    static bool inited = false;
    if (!inited) { shadowhook_init(SHADOWHOOK_MODE_SHARED, false); inited = true; }

    bool ok1 = hook_at(base + 0x64990, "MD5UPD", (void*)my_md5upd, (void**)&orig_md5upd);
    bool ok2 = hook_at(base + 0x61bf4, "GETENC", (void*)my_getEncodedP, (void**)&orig_getEncodedP);
    native_log("installHook: base=0x%lx MD5UPD=%s GETENC=%s", (unsigned long)base,
               ok1 ? "OK" : "FAIL", ok2 ? "OK" : "FAIL");
    return (ok1 || ok2) ? 0 : -3;
}