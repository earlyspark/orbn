/**
 * orbn_analysis.cpp
 *
 * JNI bridge: given a file path, decodes audio via Android MediaCodec,
 * runs the Essentia DSP pipeline (BPM, key, mel-spectrogram), then feeds
 * the mel patches into ONNX Runtime (MSD-MusiCNN embedding + mood head)
 * and returns a TrackAnalysis result to Kotlin.
 *
 * JNI entry points:
 *   analyzeTrack(assetManager, filePath) -> TrackAnalysisResult (via callback/fields)
 */

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <essentia/algorithmfactory.h>
#include <essentia/essentia.h>
#include <onnxruntime_cxx_api.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <numeric>
#include <string>
#include <vector>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG "orbn_analysis"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using clk = std::chrono::steady_clock;
static double elapsed_ms(clk::time_point a, clk::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

// ── Constants matching the MusiCNN model schema ────────────────────────────────
static constexpr int   MUSICNN_PATCH_FRAMES = 187;
static constexpr int   MUSICNN_MEL_BANDS    = 96;
static constexpr int   EMB_DIM              = 200;
static constexpr int   EMB_BATCH            = 32;      // patches per ORT run; caps peak memory
static constexpr int   TARGET_SR            = 16000;   // Essentia TensorflowInputMusiCNN SR
static constexpr int   FRAME_SIZE           = 512;
static constexpr int   HOP_SIZE             = 256;

// ── Model file names (relative to assets/models/). All heads consume the same
//    200-d MSD-MusiCNN embedding, so the embedding is computed once and reused. ──
static constexpr const char* MODEL_EMBEDDING = "models/msd-musicnn-1.onnx";
static constexpr const char* MODEL_MOOD_HAPPY = "models/mood_happy-msd-musicnn-1.onnx";
static constexpr const char* MODEL_MOOD_SAD   = "models/mood_sad-msd-musicnn-1.onnx";
static constexpr const char* MODEL_MOOD_AGGR  = "models/mood_aggressive-msd-musicnn-1.onnx";
static constexpr const char* MODEL_MOOD_RELAX = "models/mood_relaxed-msd-musicnn-1.onnx";
static constexpr const char* MODEL_GENRE      = "models/genre_rosamerica-msd-musicnn-1.onnx";
static constexpr const char* MODEL_DANCE      = "models/danceability-msd-musicnn-1.onnx";
static constexpr const char* MODEL_VOICE_INST = "models/voice_instrumental-msd-musicnn-1.onnx";

// genre_rosamerica output classes (order matches the model). Mapped to readable names.
static const char* GENRE_CLASSES[8] = {
    "classical", "dance", "hip hop", "jazz", "pop", "r&b", "rock", "speech"
};

// ─────────────────────────────────────────────────────────────────────────────
// Audio decode: MP3/FLAC/AAC → 16-kHz mono float PCM via MediaCodec NDK API
// Returns empty vector on failure.
// ─────────────────────────────────────────────────────────────────────────────
static std::vector<float> decode_audio(const char* path) {
    std::vector<float> out;

    // Open via file descriptor — path-based setDataSource is unreliable under
    // scoped storage even for app-owned files, but an fd we open ourselves works.
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        LOGE("open() failed for %s (errno %d)", path, errno);
        return out;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0) {
        LOGE("fstat failed for %s", path);
        close(fd);
        return out;
    }

    AMediaExtractor* ex = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd(ex, fd, 0, (off64_t)st.st_size) != AMEDIA_OK) {
        LOGE("MediaExtractor: cannot open fd for %s", path);
        AMediaExtractor_delete(ex);
        close(fd);
        return out;
    }

    // Find first audio track
    int audioTrack = -1;
    AMediaFormat* fmt = nullptr;
    for (size_t i = 0; i < AMediaExtractor_getTrackCount(ex); ++i) {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, i);
        const char* mime = nullptr;
        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime && strncmp(mime, "audio/", 6) == 0) {
            audioTrack = (int)i;
            fmt = f;
            break;
        }
        AMediaFormat_delete(f);
    }
    if (audioTrack < 0) {
        LOGE("No audio track in %s", path);
        AMediaExtractor_delete(ex);
        close(fd);
        return out;
    }

    // Get source sample rate and channel count
    int32_t srcSr = TARGET_SR, channels = 1;
    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &srcSr);
    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);
    // Guard malformed headers (0/negative) to avoid divide-by-zero downstream.
    if (channels < 1) channels = 1;
    if (srcSr < 1) srcSr = TARGET_SR;

    const char* mime = nullptr;
    AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
    std::string mimeStr = mime ? mime : "";

    AMediaExtractor_selectTrack(ex, audioTrack);

    AMediaCodec* codec = AMediaCodec_createDecoderByType(mimeStr.c_str());
    if (!codec) {
        LOGE("No decoder for %s", mimeStr.c_str());
        AMediaFormat_delete(fmt);
        AMediaExtractor_delete(ex);
        close(fd);
        return out;
    }

    // Configure decoder. MediaCodec outputs PCM 16-bit by default for audio.
    AMediaCodec_configure(codec, fmt, nullptr, nullptr, 0);
    AMediaCodec_start(codec);
    AMediaFormat_delete(fmt);

    // Decode loop
    std::vector<int16_t> rawPcm;
    rawPcm.reserve(srcSr * 60 * channels);  // pre-alloc ~1 minute
    bool inputDone = false, outputDone = false;
    constexpr int64_t TIMEOUT_US = 5000;

    while (!outputDone) {
        if (!inputDone) {
            ssize_t ibuf = AMediaCodec_dequeueInputBuffer(codec, TIMEOUT_US);
            if (ibuf >= 0) {
                size_t bufSize = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(codec, (size_t)ibuf, &bufSize);
                ssize_t read = AMediaExtractor_readSampleData(ex, buf, bufSize);
                if (read < 0) {
                    AMediaCodec_queueInputBuffer(codec, (size_t)ibuf, 0, 0,
                        AMediaExtractor_getSampleTime(ex),
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    int64_t pts = AMediaExtractor_getSampleTime(ex);
                    AMediaCodec_queueInputBuffer(codec, (size_t)ibuf, 0, (size_t)read, pts, 0);
                    AMediaExtractor_advance(ex);
                }
            }
        }

        AMediaCodecBufferInfo info;
        ssize_t obuf = AMediaCodec_dequeueOutputBuffer(codec, &info, TIMEOUT_US);
        if (obuf >= 0) {
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                outputDone = true;
            }
            if (info.size > 0) {
                size_t outSize = 0;
                uint8_t* outBuf = AMediaCodec_getOutputBuffer(codec, (size_t)obuf, &outSize);
                size_t samples = info.size / sizeof(int16_t);
                const int16_t* s = reinterpret_cast<const int16_t*>(outBuf + info.offset);
                rawPcm.insert(rawPcm.end(), s, s + samples);
            }
            AMediaCodec_releaseOutputBuffer(codec, (size_t)obuf, false);
        }
    }

    AMediaCodec_stop(codec);
    AMediaCodec_delete(codec);
    AMediaExtractor_delete(ex);
    close(fd);

    if (rawPcm.empty()) return out;

    // Downmix to mono if stereo (or N-channel)
    size_t monoSamples = rawPcm.size() / (size_t)channels;
    std::vector<float> mono(monoSamples);
    for (size_t i = 0; i < monoSamples; ++i) {
        float s = 0.f;
        for (int c = 0; c < channels; ++c)
            s += (float)rawPcm[i * (size_t)channels + (size_t)c] / 32768.f;
        mono[i] = s / (float)channels;
    }

    // Resample to TARGET_SR if needed (simple linear interpolation)
    if (srcSr == TARGET_SR) {
        out = std::move(mono);
    } else {
        double ratio = (double)TARGET_SR / (double)srcSr;
        size_t outLen = (size_t)((double)monoSamples * ratio);
        out.resize(outLen);
        for (size_t i = 0; i < outLen; ++i) {
            double src_idx = (double)i / ratio;
            size_t lo = (size_t)src_idx;
            size_t hi = std::min(lo + 1, monoSamples - 1);
            float frac = (float)(src_idx - (double)lo);
            out[i] = mono[lo] * (1.f - frac) + mono[hi] * frac;
        }
    }

    LOGI("Decoded %s → %zu samples @ %d Hz (src %d Hz, %d ch)",
         path, out.size(), TARGET_SR, srcSr, channels);
    return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// Load an ONNX model from Android assets into a memory buffer.
// Needed because ORT on Android needs the model bytes, not a file path.
// ─────────────────────────────────────────────────────────────────────────────
static std::vector<uint8_t> load_asset(AAssetManager* mgr, const char* name) {
    AAsset* a = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
    if (!a) { LOGE("Asset not found: %s", name); return {}; }
    size_t sz = (size_t)AAsset_getLength(a);
    std::vector<uint8_t> buf(sz);
    AAsset_read(a, buf.data(), sz);
    AAsset_close(a);
    return buf;
}

// ─────────────────────────────────────────────────────────────────────────────
// Run one classification head (a small model taking the 200-d embedding and
// producing class activations). Returns the activation vector (empty on failure).
// ─────────────────────────────────────────────────────────────────────────────
static std::vector<float> run_head(Ort::Env& ortEnv, Ort::SessionOptions& so,
                                   AAssetManager* mgr, const char* modelName,
                                   const std::vector<float>& emb,
                                   Ort::MemoryInfo& memInfo) {
    auto buf = load_asset(mgr, modelName);
    if (buf.empty()) return {};
    Ort::Session sess(ortEnv, buf.data(), buf.size(), so);
    std::vector<int64_t> shape = {1, (int64_t)emb.size()};
    Ort::Value input = Ort::Value::CreateTensor<float>(
        memInfo, const_cast<float*>(emb.data()), emb.size(), shape.data(), shape.size());
    const char* inNames[]  = {"embeddings"};
    const char* outNames[] = {"activations"};
    auto out = sess.Run(Ort::RunOptions{nullptr}, inNames, &input, 1, outNames, 1);
    float* d = out[0].GetTensorMutableData<float>();
    size_t n = out[0].GetTensorTypeAndShapeInfo().GetElementCount();
    return std::vector<float>(d, d + n);
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: analyzeTrack
// Signature: (Landroid/content/res/AssetManager;Ljava/lang/String;)
//            Lcom/earlyspark/orbn/analysis/TrackAnalysis;
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jobject JNICALL
Java_com_earlyspark_orbn_analysis_AudioAnalyzer_analyzeTrack(
        JNIEnv* env, jobject /* this */,
        jobject assetMgr, jstring jFilePath) {

    // ── Resolve Kotlin TrackAnalysis class + constructor ──────────────────────
    jclass taClass = env->FindClass("com/earlyspark/orbn/analysis/TrackAnalysis");
    if (!taClass) { LOGE("TrackAnalysis class not found"); return nullptr; }
    jmethodID ctor = env->GetMethodID(taClass, "<init>",
        "(FFFFFLjava/lang/String;FLjava/lang/String;Ljava/util/List;Ljava/util/List;FF)V");
    if (!ctor) { LOGE("TrackAnalysis ctor not found"); return nullptr; }
    jclass listClass    = env->FindClass("java/util/ArrayList");
    jmethodID listCtor  = env->GetMethodID(listClass, "<init>", "()V");
    jmethodID listAdd   = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
    jclass floatClass   = env->FindClass("java/lang/Float");
    jmethodID floatOf   = env->GetStaticMethodID(floatClass, "valueOf", "(F)Ljava/lang/Float;");

    auto autoTimer = clk::now();

    // ── Decode audio ──────────────────────────────────────────────────────────
    const char* cPath = env->GetStringUTFChars(jFilePath, nullptr);
    std::string filePath(cPath);
    env->ReleaseStringUTFChars(jFilePath, cPath);

    auto t0 = clk::now();
    std::vector<float> audio = decode_audio(filePath.c_str());
    auto t1 = clk::now();
    if (audio.empty()) {
        LOGE("Audio decode failed for %s", filePath.c_str());
        return nullptr;
    }
    LOGI("Decode: %.1f ms, %zu samples", elapsed_ms(t0, t1), audio.size());

    // ── Essentia: BPM + Key + Mel spectrogram ─────────────────────────────────
    essentia::init();
    auto& F = essentia::standard::AlgorithmFactory::instance();

    // BPM
    auto t2 = clk::now();
    auto* rhythmExtractor = F.create("RhythmExtractor2013", "method", "multifeature");
    essentia::Real bpm = 0.f, confidence = 0.f;
    std::vector<essentia::Real> ticks, estimates, bpmIntervals;
    rhythmExtractor->input("signal").set(audio);
    rhythmExtractor->output("bpm").set(bpm);
    rhythmExtractor->output("ticks").set(ticks);
    rhythmExtractor->output("confidence").set(confidence);
    rhythmExtractor->output("estimates").set(estimates);
    rhythmExtractor->output("bpmIntervals").set(bpmIntervals);
    rhythmExtractor->compute();
    delete rhythmExtractor;
    auto t3 = clk::now();
    LOGI("BPM: %.1f (confidence %.2f)  %.1f ms", bpm, confidence, elapsed_ms(t2, t3));

    // Key
    auto* keyExtractor = F.create("KeyExtractor");
    std::string keyName, keyScale;
    essentia::Real keyStrength = 0.f;
    keyExtractor->input("audio").set(audio);
    keyExtractor->output("key").set(keyName);
    keyExtractor->output("scale").set(keyScale);
    keyExtractor->output("strength").set(keyStrength);
    keyExtractor->compute();
    delete keyExtractor;
    std::string keyFull = keyName + " " + keyScale;
    LOGI("Key: %s (strength %.2f)", keyFull.c_str(), keyStrength);

    // Loudness as RMS (length-independent, unlike total Energy which grows with
    // duration). RMS = sqrt(sum(x^2)/N); for normalized PCM this sits ~0–0.5,
    // so scale into a usable 0–1 range.
    auto* energyAlgo = F.create("Energy");
    essentia::Real energySum = 0.f;
    energyAlgo->input("array").set(audio);
    energyAlgo->output("energy").set(energySum);
    energyAlgo->compute();
    delete energyAlgo;
    float rms = std::sqrt(energySum / (float)std::max<size_t>(1, audio.size()));
    float loudnessNorm = std::min(1.f, rms * 3.0f);  // empirical scale (input feature)

    // Mel spectrogram (TensorflowInputMusiCNN = 96-band mel @ 16kHz)
    auto t4 = clk::now();
    auto* fc  = F.create("FrameCutter",
                          "frameSize", FRAME_SIZE, "hopSize", HOP_SIZE,
                          "startFromZero", true);
    auto* mel = F.create("TensorflowInputMusiCNN");
    std::vector<essentia::Real> frame, bands;
    fc->input("signal").set(audio);
    fc->output("frame").set(frame);
    mel->input("frame").set(frame);
    mel->output("bands").set(bands);

    std::vector<float> melFlat;
    melFlat.reserve(audio.size() / HOP_SIZE * MUSICNN_MEL_BANDS);
    while (true) {
        fc->compute();
        if (frame.empty()) break;
        mel->compute();
        melFlat.insert(melFlat.end(), bands.begin(), bands.end());
    }
    delete fc;
    delete mel;

    int nFrames  = (int)melFlat.size() / MUSICNN_MEL_BANDS;
    int nPatches = nFrames / MUSICNN_PATCH_FRAMES;
    if (nPatches < 1) {
        LOGE("Track too short for MusiCNN (only %d mel frames)", nFrames);
        essentia::shutdown();
        return nullptr;
    }
    melFlat.resize((size_t)nPatches * MUSICNN_PATCH_FRAMES * MUSICNN_MEL_BANDS);
    auto t5 = clk::now();
    LOGI("Mel: %d frames → %d patches  %.1f ms", nFrames, nPatches, elapsed_ms(t4, t5));

    essentia::shutdown();

    // ── ONNX Runtime: MusiCNN embedding (once) → mood + genre heads ───────────
    AAssetManager* mgr = AAssetManager_fromJava(env, assetMgr);
    auto embBuf = load_asset(mgr, MODEL_EMBEDDING);
    if (embBuf.empty()) return nullptr;

    Ort::Env ortEnv(ORT_LOGGING_LEVEL_WARNING, "orbn");
    Ort::SessionOptions so;
    so.SetIntraOpNumThreads(0);  // all cores (Dimensity 7050 is octa-core)

    Ort::Session embSess(ortEnv, embBuf.data(), embBuf.size(), so);

    auto memInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    const char* embInNames[]  = {"melspectrogram"};
    const char* embOutNames[] = {"embeddings"};

    // Run the embedding model in fixed-size mini-batches and mean-pool patches
    // incrementally. Peak inference memory scales with the batch dimension, so
    // feeding a whole track at once makes a long track (hundreds of patches)
    // allocate multiple GB of intermediate activations — enough to trip the
    // low-memory killer on a modest device. Capping the batch bounds peak memory
    // to a constant regardless of track length; the mean over patches is
    // associative, so the result is identical to a single full-track run.
    const size_t patchSize = (size_t)MUSICNN_PATCH_FRAMES * MUSICNN_MEL_BANDS;
    std::vector<float> meanEmb(EMB_DIM, 0.f);
    auto t6 = clk::now();
    for (int start = 0; start < nPatches; start += EMB_BATCH) {
        int batch = std::min(EMB_BATCH, nPatches - start);
        std::vector<int64_t> embShape = {batch, MUSICNN_PATCH_FRAMES, MUSICNN_MEL_BANDS};
        Ort::Value embInput = Ort::Value::CreateTensor<float>(
            memInfo, melFlat.data() + (size_t)start * patchSize,
            (size_t)batch * patchSize, embShape.data(), embShape.size());
        auto embOut = embSess.Run(Ort::RunOptions{nullptr},
                                  embInNames, &embInput, 1, embOutNames, 1);
        const float* embData = embOut[0].GetTensorMutableData<float>();
        for (int k = 0; k < batch; ++k)
            for (int d = 0; d < EMB_DIM; ++d)
                meanEmb[d] += embData[k * EMB_DIM + d];
    }
    for (auto& v : meanEmb) v /= (float)nPatches;
    auto t7 = clk::now();
    LOGI("ORT embedding: %.1f ms (%d patches, batch %d)",
         elapsed_ms(t6, t7), nPatches, EMB_BATCH);

    // Run the classification heads (each tiny; all consume the same embedding).
    // Class orderings differ per head (from each model's metadata):
    //   mood_happy:      [happy, non_happy]        → happy at [0]
    //   mood_sad:        [non_sad, sad]            → sad   at [1]
    //   mood_aggressive: [aggressive, not_aggr.]   → aggr  at [0]
    //   mood_relaxed:    [non_relaxed, relaxed]    → relax at [1]
    auto t8 = clk::now();
    auto happy = run_head(ortEnv, so, mgr, MODEL_MOOD_HAPPY, meanEmb, memInfo);
    auto sad   = run_head(ortEnv, so, mgr, MODEL_MOOD_SAD,   meanEmb, memInfo);
    auto aggr  = run_head(ortEnv, so, mgr, MODEL_MOOD_AGGR,  meanEmb, memInfo);
    auto relax = run_head(ortEnv, so, mgr, MODEL_MOOD_RELAX, meanEmb, memInfo);
    auto genre = run_head(ortEnv, so, mgr, MODEL_GENRE,      meanEmb, memInfo);
    auto dance = run_head(ortEnv, so, mgr, MODEL_DANCE,      meanEmb, memInfo);
    auto voice = run_head(ortEnv, so, mgr, MODEL_VOICE_INST, meanEmb, memInfo);
    auto t9 = clk::now();
    LOGI("ORT heads (7): %.1f ms", elapsed_ms(t8, t9));

    if (happy.size() < 2 || sad.size() < 2 || aggr.size() < 2 ||
        relax.size() < 2 || genre.size() < 8 || dance.size() < 2 || voice.size() < 2) {
        LOGE("A classification head failed to produce output");
        return nullptr;
    }

    float happyScore = happy[0];
    float sadScore   = sad[1];
    float aggrScore  = aggr[0];
    float relaxScore = relax[1];
    //   danceability:       [danceable, not_danceable]  → danceable    at [0]
    //   voice_instrumental: [instrumental, voice]       → instrumental at [0]
    float danceScore = dance[0];
    float instrScore = voice[0];

    // Affect plane: valence (pleasant↔unpleasant), energy (calm↔energetic activation).
    float valence = std::clamp((happyScore + (1.f - sadScore)) / 2.f, 0.f, 1.f);
    float energyAxis = std::clamp((aggrScore + (1.f - relaxScore)) / 2.f, 0.f, 1.f);

    // Genre = argmax over the 8 rosamerica classes.
    int genreIdx = (int)(std::max_element(genre.begin(), genre.end()) - genre.begin());
    float genreConf = genre[genreIdx];
    const char* genreName = GENRE_CLASSES[genreIdx];

    // mood tag list (names + scores) for "why this track" / debugging.
    jobject tagNames  = env->NewObject(listClass, listCtor);
    jobject tagScores = env->NewObject(listClass, listCtor);
    const char* moodNames[4] = {"happy", "sad", "aggressive", "relaxed"};
    float moodScores[4] = {happyScore, sadScore, aggrScore, relaxScore};
    for (int i = 0; i < 4; ++i) {
        env->CallBooleanMethod(tagNames,  listAdd, env->NewStringUTF(moodNames[i]));
        env->CallBooleanMethod(tagScores, listAdd,
            env->CallStaticObjectMethod(floatClass, floatOf, moodScores[i]));
    }

    double totalMs = elapsed_ms(autoTimer, clk::now());
    LOGI("=== TrackAnalysis complete: %.1f ms total ===", totalMs);
    LOGI("  BPM=%.1f Key=%s Loudness=%.3f Valence=%.3f Energy=%.3f Genre=%s(%.2f) Dance=%.2f Instr=%.2f",
         bpm, keyFull.c_str(), loudnessNorm, valence, energyAxis, genreName, genreConf,
         danceScore, instrScore);

    // ── Construct TrackAnalysis Kotlin object ─────────────────────────────────
    // Argument order matches TrackAnalysis: bpm, keyStrength, loudness, valence,
    // energy, genre, genreConfidence, key, moodTagNames, moodTagScores,
    // danceability, voiceInstrumental.
    jstring jKey   = env->NewStringUTF(keyFull.c_str());
    jstring jGenre = env->NewStringUTF(genreName);
    return env->NewObject(taClass, ctor,
        (jfloat)bpm,
        (jfloat)keyStrength,
        (jfloat)loudnessNorm,
        (jfloat)valence,
        (jfloat)energyAxis,
        jGenre,
        (jfloat)genreConf,
        jKey,
        tagNames,
        tagScores,
        (jfloat)danceScore,
        (jfloat)instrScore);
}
