cmake_minimum_required(VERSION 3.22.1)
project(orbn_analysis)

# ── paths relative to this CMakeLists.txt ─────────────────────────────────────
set(LIBS_DIR   "${CMAKE_SOURCE_DIR}/../../../libs")
set(ESSENTIA   "${LIBS_DIR}/essentia")
set(ORT        "${LIBS_DIR}/ort")
set(EIGEN      "${LIBS_DIR}/eigen")

# ── ONNX Runtime: pre-built .so delivered via jniLibs ─────────────────────────
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION
        "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so")

# ── Essentia: pre-built static archive ────────────────────────────────────────
add_library(essentia STATIC IMPORTED)
set_target_properties(essentia PROPERTIES
    IMPORTED_LOCATION "${ESSENTIA}/libessentia.a")

# ── orbn_analysis JNI shared library ──────────────────────────────────────────
add_library(orbn_analysis SHARED orbn_analysis.cpp)

target_include_directories(orbn_analysis PRIVATE
    "${ESSENTIA}/include"          # essentia/*.h
    "${ORT}/include"               # onnxruntime_cxx_api.h etc.
    "${EIGEN}"                     # Eigen/
)

target_compile_options(orbn_analysis PRIVATE
    -O2
    -ffast-math
    -DFIXED_POINT          # kiss_fft option (matches how Essentia was built)
    -fexceptions
    -frtti
)

# Android system libs
find_library(log-lib     log)
find_library(mediandk    mediandk)   # MediaExtractor / MediaCodec / MediaFormat
find_library(android-lib android)   # AAssetManager

target_link_libraries(orbn_analysis
    essentia
    onnxruntime
    ${log-lib}
    ${mediandk}
    ${android-lib}
)
