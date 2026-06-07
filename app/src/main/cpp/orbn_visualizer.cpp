// orbn_visualizer.cpp — JNI bridge to projectM v4 (M6 visualizer spike, P2).
//
// Thin wrapper over projectM's C API. All calls must run on the GL thread with a current GLES 3
// context (the GLSurfaceView renderer guarantees this) — projectm_create() initialises projectM's
// GL function resolver from the current context, and rendering targets the bound framebuffer.
//
// P3: live ExoPlayer PCM (tapped via a TeeAudioProcessor in PlaybackService, handed across the
// AudioTap bridge) drives the visuals. nativeAddPcm() feeds projectM the decoded mono samples;
// when nothing is playing no PCM arrives and projectM's analysis naturally settles.

#include <jni.h>
#include <android/log.h>

#include "projectM-4/projectM.h"

#define LOG_TAG "orbn_visualizer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
// Single instance for the spike (one visualizer surface at a time).
projectm_handle g_pm = nullptr;
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

// Switch presets at runtime (double-tap in the UI). `true` = smooth blend into the new preset.
JNIEXPORT void JNICALL
Java_com_earlyspark_orbn_visualizer_NativeVisualizer_nativeLoadPreset(
        JNIEnv* env, jobject /*thiz*/, jstring jPresetPath) {
    if (g_pm == nullptr) return;
    const char* path = env->GetStringUTFChars(jPresetPath, nullptr);
    if (path != nullptr && path[0] != '\0') {
        projectm_load_preset_file(g_pm, path, true);
        LOGI("switched preset: %s", path);
    }
    if (path != nullptr) env->ReleaseStringUTFChars(jPresetPath, path);
}

// Feed decoded mono PCM (from the live audio tap) into projectM's analysis buffer. Called on the
// GL thread, just before nativeRenderFrame, with whatever the renderer pulled from AudioTap.
JNIEXPORT void JNICALL
Java_com_earlyspark_orbn_visualizer_NativeVisualizer_nativeAddPcm(
        JNIEnv* env, jobject /*thiz*/, jfloatArray jpcm, jint count) {
    if (g_pm == nullptr || jpcm == nullptr || count <= 0) return;
    jfloat* data = env->GetFloatArrayElements(jpcm, nullptr);
    if (data != nullptr) {
        projectm_pcm_add_float(g_pm, data, static_cast<unsigned int>(count), PROJECTM_MONO);
        env->ReleaseFloatArrayElements(jpcm, data, JNI_ABORT);  // read-only; don't copy back
    }
}

JNIEXPORT void JNICALL
Java_com_earlyspark_orbn_visualizer_NativeVisualizer_nativeRenderFrame(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_pm == nullptr) return;
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
