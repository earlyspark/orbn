// orbn_visualizer.cpp — JNI bridge to projectM v4 (M6 visualizer spike, P2).
//
// Thin wrapper over projectM's C API. All calls must run on the GL thread with a current GLES 3
// context (the GLSurfaceView renderer guarantees this) — projectm_create() initialises projectM's
// GL function resolver from the current context, and rendering targets the bound framebuffer.
//
// P2 feeds a synthesized waveform (dummy PCM) so the visuals are visibly reactive without real
// audio; P3 swaps in the live ExoPlayer PCM tap.

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdlib>

#include "projectM-4/projectM.h"

#define LOG_TAG "orbn_visualizer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
// Single instance for the spike (one visualizer surface at a time).
projectm_handle g_pm = nullptr;
double g_phase = 0.0;
}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_earlyspark_orbn_visualizer_NativeVisualizer_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring jPresetPath, jint width, jint height) {
    if (g_pm != nullptr) {
        projectm_destroy(g_pm);
        g_pm = nullptr;
    }
    g_pm = projectm_create();  // GL context must be current on this (GL) thread
    if (g_pm == nullptr) {
        LOGE("projectm_create() returned null");
        return;
    }
    projectm_set_window_size(g_pm, static_cast<size_t>(width), static_cast<size_t>(height));
    projectm_set_mesh_size(g_pm, 48, 32);
    projectm_set_fps(g_pm, 60);

    const char* path = env->GetStringUTFChars(jPresetPath, nullptr);
    if (path != nullptr && path[0] != '\0') {
        projectm_load_preset_file(g_pm, path, false);
        LOGI("loaded preset: %s", path);
    }
    if (path != nullptr) env->ReleaseStringUTFChars(jPresetPath, path);

    LOGI("projectM init %dx%d, version %s", width, height, projectm_get_version_string());
}

JNIEXPORT void JNICALL
Java_com_earlyspark_orbn_visualizer_NativeVisualizer_nativeResize(
        JNIEnv* /*env*/, jobject /*thiz*/, jint width, jint height) {
    if (g_pm != nullptr) {
        projectm_set_window_size(g_pm, static_cast<size_t>(width), static_cast<size_t>(height));
    }
}

JNIEXPORT void JNICALL
Java_com_earlyspark_orbn_visualizer_NativeVisualizer_nativeRenderFrame(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_pm == nullptr) return;

    // Dummy PCM: a drifting multi-tone wave + a little noise, so the preset visibly reacts.
    // (P3 replaces this with the live decoded audio from ExoPlayer.)
    constexpr unsigned int kSamples = 512;
    static float buf[kSamples];
    for (unsigned int i = 0; i < kSamples; ++i) {
        const double t = g_phase + i * 0.05;
        const double noise = (std::rand() % 1000) / 1000.0 - 0.5;
        buf[i] = static_cast<float>(0.5 * std::sin(t) + 0.3 * std::sin(t * 2.7) + 0.1 * noise);
    }
    g_phase += 0.3;

    projectm_pcm_add_float(g_pm, buf, kSamples, PROJECTM_MONO);
    projectm_opengl_render_frame(g_pm);
}

JNIEXPORT void JNICALL
Java_com_earlyspark_orbn_visualizer_NativeVisualizer_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_pm != nullptr) {
        projectm_destroy(g_pm);
        g_pm = nullptr;
    }
}

}  // extern "C"
