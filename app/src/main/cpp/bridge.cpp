// Frontend libretro tối giản: nạp core (.so), chạy game, đẩy hình lên Surface,
// gom âm thanh trả về Java, nhận trạng thái phím từ Java.
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define TAG "MiniRetro"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Phần tối thiểu của libretro.h
// ---------------------------------------------------------------------------
struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};
struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};
struct retro_system_timing {
    double fps;
    double sample_rate;
};
struct retro_system_av_info {
    retro_game_geometry geometry;
    retro_system_timing timing;
};
struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};
struct retro_variable {
    const char *key;
    const char *value;
};
typedef void (*retro_log_printf_t)(int level, const char *fmt, ...);
struct retro_log_callback {
    retro_log_printf_t log;
};

typedef bool (*retro_environment_t)(unsigned, void *);
typedef void (*retro_video_refresh_t)(const void *, unsigned, unsigned, size_t);
typedef void (*retro_audio_sample_t)(int16_t, int16_t);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *, size_t);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned, unsigned, unsigned, unsigned);

struct Core {
    void *h = nullptr;
    std::string path;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)(void) = nullptr;
    void (*deinit)(void) = nullptr;
    void (*get_system_info)(retro_system_info *) = nullptr;
    void (*get_system_av_info)(retro_system_av_info *) = nullptr;
    void (*run)(void) = nullptr;
    bool (*load_game)(const retro_game_info *) = nullptr;
    void (*unload_game)(void) = nullptr;
    void *(*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;
};

// ---------------------------------------------------------------------------
// Trạng thái toàn cục
// ---------------------------------------------------------------------------
static Core g_core;
static bool g_loaded = false;
static std::string g_system_dir, g_save_dir, g_sram_path;
static int g_pixfmt = 0;  // 0 = 0RGB1555, 1 = XRGB8888, 2 = RGB565
static retro_system_av_info g_av;
static std::vector<uint8_t> g_rom;

static const size_t AUDIO_CAP = 32768;
static int16_t g_audio[AUDIO_CAP];
static size_t g_audio_n = 0;

static volatile int g_input[2] = {0, 0};

static std::mutex g_win_mtx;
static ANativeWindow *g_win = nullptr;
static int g_win_w = 0, g_win_h = 0, g_win_fmt = 0;
static std::vector<uint8_t> g_last_raw;
static unsigned g_last_w = 0, g_last_h = 0;
static size_t g_last_pitch = 0;
static int g_last_fmt = 0;

// ---------------------------------------------------------------------------
// Vẽ khung hình (gọi khi đã giữ g_win_mtx)
// ---------------------------------------------------------------------------
static void blit(const void *data, unsigned w, unsigned h, size_t pitch, int fmt) {
    if (!g_win || !data || !w || !h) return;
    int wfmt = (fmt == 1) ? WINDOW_FORMAT_RGBX_8888 : WINDOW_FORMAT_RGB_565;
    if ((int)w != g_win_w || (int)h != g_win_h || wfmt != g_win_fmt) {
        if (ANativeWindow_setBuffersGeometry(g_win, (int32_t)w, (int32_t)h, wfmt) != 0) return;
        g_win_w = (int)w;
        g_win_h = (int)h;
        g_win_fmt = wfmt;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(g_win, &buf, nullptr) != 0) return;

    unsigned cw = std::min<unsigned>(w, (unsigned)buf.width);
    unsigned ch = std::min<unsigned>(h, (unsigned)buf.height);
    uint8_t *dst = (uint8_t *)buf.bits;
    const uint8_t *src = (const uint8_t *)data;

    if (fmt == 2) {  // RGB565 -> RGB565
        for (unsigned y = 0; y < ch; y++)
            memcpy(dst + (size_t)y * buf.stride * 2, src + (size_t)y * pitch, (size_t)cw * 2);
    } else if (fmt == 1) {  // XRGB8888 -> RGBX8888 (đổi chỗ R và B)
        for (unsigned y = 0; y < ch; y++) {
            uint32_t *d = (uint32_t *)(dst + (size_t)y * buf.stride * 4);
            const uint32_t *s = (const uint32_t *)(src + (size_t)y * pitch);
            for (unsigned x = 0; x < cw; x++) {
                uint32_t p = s[x];
                d[x] = 0xFF000000u | ((p & 0xFFu) << 16) | (p & 0xFF00u) | ((p >> 16) & 0xFFu);
            }
        }
    } else {  // 0RGB1555 -> RGB565
        for (unsigned y = 0; y < ch; y++) {
            uint16_t *d = (uint16_t *)(dst + (size_t)y * buf.stride * 2);
            const uint16_t *s = (const uint16_t *)(src + (size_t)y * pitch);
            for (unsigned x = 0; x < cw; x++) {
                uint16_t p = s[x];
                uint16_t r = (p >> 10) & 31, g = (p >> 5) & 31, b = p & 31;
                d[x] = (uint16_t)((r << 11) | (((g << 1) | (g >> 4)) << 5) | b);
            }
        }
    }
    ANativeWindow_unlockAndPost(g_win);
}

// ---------------------------------------------------------------------------
// Callback cho core
// ---------------------------------------------------------------------------
static void log_cb(int level, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(level >= 3 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "MiniRetroCore", "%s", buf);
}

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || data == (const void *)-1) return;  // khung trùng / hw render
    std::lock_guard<std::mutex> lk(g_win_mtx);
    g_last_raw.assign((const uint8_t *)data, (const uint8_t *)data + pitch * h);
    g_last_w = w;
    g_last_h = h;
    g_last_pitch = pitch;
    g_last_fmt = g_pixfmt;
    blit(g_last_raw.data(), w, h, pitch, g_pixfmt);
}

static void audio_sample_cb(int16_t l, int16_t r) {
    if (g_audio_n + 2 <= AUDIO_CAP) {
        g_audio[g_audio_n++] = l;
        g_audio[g_audio_n++] = r;
    }
}

static size_t audio_batch_cb(const int16_t *d, size_t frames) {
    size_t n = frames * 2;
    if (g_audio_n + n > AUDIO_CAP) n = (AUDIO_CAP - g_audio_n) & ~(size_t)1;
    if (n) {
        memcpy(g_audio + g_audio_n, d, n * sizeof(int16_t));
        g_audio_n += n;
    }
    return frames;
}

static void input_poll_cb(void) {}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port > 1) return 0;
    if ((device & 0xFF) == 1 && id < 16)  // RETRO_DEVICE_JOYPAD
        return (int16_t)((g_input[port] >> id) & 1);
    return 0;
}

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case 3:  *(bool *)data = true; return true;                       // GET_CAN_DUPE
        case 6:  return true;                                              // SET_MESSAGE
        case 8:  return true;                                              // SET_PERFORMANCE_LEVEL
        case 9:  *(const char **)data = g_system_dir.c_str(); return true; // GET_SYSTEM_DIRECTORY
        case 10: {                                                         // SET_PIXEL_FORMAT
            int f = *(const int *)data;
            if (f >= 0 && f <= 2) { g_pixfmt = f; return true; }
            return false;
        }
        case 11: return true;                                              // SET_INPUT_DESCRIPTORS
        case 15: ((retro_variable *)data)->value = nullptr; return false; // GET_VARIABLE (dùng mặc định)
        case 16: return true;                                              // SET_VARIABLES
        case 17: *(bool *)data = false; return true;                       // GET_VARIABLE_UPDATE
        case 18: return true;                                              // SET_SUPPORT_NO_GAME
        case 24: *(uint64_t *)data = (1ULL << 1); return true;             // GET_INPUT_DEVICE_CAPABILITIES
        case 27: ((retro_log_callback *)data)->log = log_cb; return true; // GET_LOG_INTERFACE
        case 31: *(const char **)data = g_save_dir.c_str(); return true;   // GET_SAVE_DIRECTORY
        case 32: g_av = *(const retro_system_av_info *)data; return true;  // SET_SYSTEM_AV_INFO
        case 35: return true;                                              // SET_CONTROLLER_INFO
        case 37: g_av.geometry = *(const retro_game_geometry *)data; return true; // SET_GEOMETRY
        case 39: *(unsigned *)data = 0; return true;                       // GET_LANGUAGE (English)
        default: return false;
    }
}

// ---------------------------------------------------------------------------
// Nạp / gỡ core
// ---------------------------------------------------------------------------
#define LOADSYM(n)                                                    \
    do {                                                              \
        *(void **)(&g_core.n) = dlsym(h, "retro_" #n);                \
        if (!g_core.n) { err = "missing symbol retro_" #n; ok = false; } \
    } while (0)

static std::string open_core(const std::string &path) {
    if (g_core.h && g_core.path == path) return "";
    if (g_core.h) {
        dlclose(g_core.h);
        g_core = Core();
    }
    void *h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char *e = dlerror();
        return std::string("dlopen failed: ") + (e ? e : "?");
    }
    std::string err;
    bool ok = true;
    LOADSYM(set_environment);
    LOADSYM(set_video_refresh);
    LOADSYM(set_audio_sample);
    LOADSYM(set_audio_sample_batch);
    LOADSYM(set_input_poll);
    LOADSYM(set_input_state);
    LOADSYM(init);
    LOADSYM(deinit);
    LOADSYM(get_system_info);
    LOADSYM(get_system_av_info);
    LOADSYM(run);
    LOADSYM(load_game);
    LOADSYM(unload_game);
    LOADSYM(get_memory_data);
    LOADSYM(get_memory_size);
    if (!ok) {
        dlclose(h);
        g_core = Core();
        return err;
    }
    g_core.h = h;
    g_core.path = path;
    return "";
}

static void sram_op(bool save) {
    if (!g_loaded || g_sram_path.empty()) return;
    size_t sz = g_core.get_memory_size(0);  // RETRO_MEMORY_SAVE_RAM
    void *p = g_core.get_memory_data(0);
    if (!sz || !p) return;
    if (save) {
        FILE *f = fopen(g_sram_path.c_str(), "wb");
        if (f) { fwrite(p, 1, sz, f); fclose(f); }
    } else {
        FILE *f = fopen(g_sram_path.c_str(), "rb");
        if (f) { size_t r = fread(p, 1, sz, f); (void)r; fclose(f); }
    }
}

static void do_unload() {
    if (!g_loaded) return;
    sram_op(true);
    g_core.unload_game();
    g_core.deinit();
    g_loaded = false;
    g_rom.clear();
    g_rom.shrink_to_fit();
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

static std::string base_no_ext(const std::string &path) {
    size_t slash = path.find_last_of('/');
    std::string n = (slash == std::string::npos) ? path : path.substr(slash + 1);
    size_t dot = n.find_last_of('.');
    if (dot != std::string::npos) n = n.substr(0, dot);
    return n;
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jstring JNICALL
Java_com_minigame_fpt_Native_nativeLoad(JNIEnv *env, jclass, jstring jcore, jstring jgame,
                                        jstring jsys, jstring jsave) {
    std::string corePath = jstr(env, jcore);
    std::string game = jstr(env, jgame);
    do_unload();
    g_system_dir = jstr(env, jsys);
    g_save_dir = jstr(env, jsave);

    std::string err = open_core(corePath);
    if (!err.empty()) return env->NewStringUTF(err.c_str());

    g_pixfmt = 0;
    g_audio_n = 0;
    g_input[0] = g_input[1] = 0;
    memset(&g_av, 0, sizeof(g_av));
    {
        std::lock_guard<std::mutex> lk(g_win_mtx);
        g_last_raw.clear();
        g_last_w = g_last_h = 0;
        g_win_w = g_win_h = g_win_fmt = 0;
    }

    g_core.set_environment(env_cb);
    g_core.init();
    g_core.set_video_refresh(video_cb);
    g_core.set_audio_sample(audio_sample_cb);
    g_core.set_audio_sample_batch(audio_batch_cb);
    g_core.set_input_poll(input_poll_cb);
    g_core.set_input_state(input_state_cb);

    retro_system_info si;
    memset(&si, 0, sizeof(si));
    g_core.get_system_info(&si);

    retro_game_info gi;
    memset(&gi, 0, sizeof(gi));
    gi.path = game.c_str();
    g_rom.clear();
    if (!si.need_fullpath) {
        FILE *f = fopen(game.c_str(), "rb");
        if (!f) {
            g_core.deinit();
            return env->NewStringUTF("cannot open game file");
        }
        fseek(f, 0, SEEK_END);
        long len = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (len <= 0) {
            fclose(f);
            g_core.deinit();
            return env->NewStringUTF("game file is empty");
        }
        g_rom.resize((size_t)len);
        size_t r = fread(g_rom.data(), 1, (size_t)len, f);
        fclose(f);
        if (r != (size_t)len) {
            g_core.deinit();
            return env->NewStringUTF("cannot read game file");
        }
        gi.data = g_rom.data();
        gi.size = g_rom.size();
    }

    if (!g_core.load_game(&gi)) {
        g_core.deinit();
        g_rom.clear();
        return env->NewStringUTF("retro_load_game failed (missing BIOS or wrong format?)");
    }
    g_core.get_system_av_info(&g_av);
    g_loaded = true;

    g_sram_path = g_save_dir + "/" + base_no_ext(game) + ".srm";
    sram_op(false);
    LOGI("Loaded %s (%ux%u @ %.2f fps, %.0f Hz)", game.c_str(), g_av.geometry.base_width,
         g_av.geometry.base_height, g_av.timing.fps, g_av.timing.sample_rate);
    return nullptr;
}

JNIEXPORT jint JNICALL
Java_com_minigame_fpt_Native_nativeRun(JNIEnv *env, jclass, jshortArray arr) {
    if (!g_loaded) return -1;
    g_audio_n = 0;
    g_core.run();
    size_t cap = (size_t)env->GetArrayLength(arr);
    size_t n = std::min(g_audio_n, cap);
    if (n) env->SetShortArrayRegion(arr, 0, (jsize)n, (const jshort *)g_audio);
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_com_minigame_fpt_Native_nativeUnload(JNIEnv *, jclass) {
    do_unload();
}

JNIEXPORT void JNICALL
Java_com_minigame_fpt_Native_nativeSaveSram(JNIEnv *, jclass) {
    sram_op(true);
}

JNIEXPORT void JNICALL
Java_com_minigame_fpt_Native_nativeSetInput(JNIEnv *, jclass, jint port, jint mask) {
    if (port >= 0 && port <= 1) g_input[port] = mask;
}

JNIEXPORT void JNICALL
Java_com_minigame_fpt_Native_nativeSetSurface(JNIEnv *env, jclass, jobject surface) {
    std::lock_guard<std::mutex> lk(g_win_mtx);
    if (g_win) {
        ANativeWindow_release(g_win);
        g_win = nullptr;
    }
    g_win_w = g_win_h = g_win_fmt = 0;
    if (surface) g_win = ANativeWindow_fromSurface(env, surface);
}

JNIEXPORT void JNICALL
Java_com_minigame_fpt_Native_nativeRedraw(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lk(g_win_mtx);
    if (!g_last_raw.empty()) blit(g_last_raw.data(), g_last_w, g_last_h, g_last_pitch, g_last_fmt);
}

JNIEXPORT jdouble JNICALL
Java_com_minigame_fpt_Native_nativeGetSampleRate(JNIEnv *, jclass) {
    return g_av.timing.sample_rate;
}

JNIEXPORT jdouble JNICALL
Java_com_minigame_fpt_Native_nativeGetFps(JNIEnv *, jclass) {
    return g_av.timing.fps;
}

JNIEXPORT jfloat JNICALL
Java_com_minigame_fpt_Native_nativeGetAspect(JNIEnv *, jclass) {
    float a = g_av.geometry.aspect_ratio;
    if (a <= 0.1f && g_av.geometry.base_height > 0)
        a = (float)g_av.geometry.base_width / (float)g_av.geometry.base_height;
    if (a <= 0.1f) a = 4.0f / 3.0f;
    return a;
}

}  // extern "C"
