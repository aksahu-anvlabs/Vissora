#define NOMINMAX

#include <jni.h>
#include "global.h"
#include <fstream>
#include <iostream>
#include <filesystem>
#include <cstdint>
#include <vector>
#include <memory>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <atomic>
#include <new>
#include <thread>
#include <chrono>
#include "ncnn/mat.h"

#include "stb_image_write.h"
#include "ByteTrack/BYTETracker.h"
#include "ByteTrack/Object.h"
#include "ByteTrack/Rect.h"
#include "ByteTrack/STrack.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
}

struct DesktopPlayerCtx {
    AVFormatContext* fmt_ctx = nullptr;
    AVCodecContext* codec_ctx = nullptr;
    int video_stream_idx = -1;
    AVFrame* raw_frame = nullptr;
    SwsContext* sws_ctx = nullptr;
    int width = 0;
    int height = 0;
    bool yolo = false;
    const char* name;
    std::vector<int> classes;
    
    // All tracking and inference state is now isolated per-camera
    std::shared_ptr<std::map<size_t, std::string>> trackIds = std::make_shared<std::map<size_t, std::string>>();
    std::shared_ptr<std::vector<byte_track::BYTETracker::STrackPtr>> res = std::make_shared<std::vector<byte_track::BYTETracker::STrackPtr>>();
    std::atomic<bool> running{false};
    byte_track::BYTETracker tracker{30, 30, 0.5f, 0.6f, 0.8f};
};

static void destroyPlayer(DesktopPlayerCtx* ctx) {
    if (!ctx) return;
    
    // Wait for the background inference thread to finish to prevent segfaults
    while (ctx->running.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    
    if (ctx->sws_ctx) sws_freeContext(ctx->sws_ctx);
    if (ctx->raw_frame) av_frame_free(&ctx->raw_frame);
    if (ctx->codec_ctx) avcodec_free_context(&ctx->codec_ctx);
    if (ctx->fmt_ctx) avformat_close_input(&ctx->fmt_ctx);
    delete ctx;
}

#if defined(__ANDROID__) && __ANDROID_API__ < 28
namespace fs = std::__fs::filesystem;
#else
namespace fs = std::filesystem;
#endif

extern "C" {

JNIEXPORT void JNICALL Java_org_anvlabs_vissora_VideoStream_processYOLO(JNIEnv* env, jobject thiz, jlong handle, jintArray array) {
    auto* ctx = reinterpret_cast<DesktopPlayerCtx*>(handle);
    if (!ctx) {
        return;
    }
    try{
        jsize size = env->GetArrayLength(array);
        ctx->classes.resize(size);
        env->GetIntArrayRegion(array, 0, size, reinterpret_cast<jint*>(ctx->classes.data()));
        ctx->yolo = true;
    }
    catch(const std::exception& e){
        std::cout << "error: " << e.what() << std::endl;
    }
}

JNIEXPORT jlong JNICALL Java_org_anvlabs_vissora_VideoStream_openStream(JNIEnv* env, jobject thiz, jstring name, jstring url) {
    if (url == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "VissoraFFmpeg", "openStream: URL is null");
        return 0;
    }

    const char* rtsp_url = env->GetStringUTFChars(url, nullptr);
    if (rtsp_url == nullptr) return 0;

    DesktopPlayerCtx* ctx = new (std::nothrow) DesktopPlayerCtx{};
    if (ctx == nullptr) {
        env->ReleaseStringUTFChars(url, rtsp_url);
        return 0;
    }

    if (name != nullptr) {
        const char* native_name = env->GetStringUTFChars(name, nullptr);
        if (native_name == nullptr) {
            env->ReleaseStringUTFChars(url, rtsp_url);
            destroyPlayer(ctx);
            return 0;
        }
        ctx->name = native_name;
        env->ReleaseStringUTFChars(name, native_name);
    }

    AVDictionary* opts = nullptr;
    av_dict_set(&opts, "rtsp_transport", "tcp", 0);
    av_dict_set(&opts, "stimeout", "5000000", 0);
    av_dict_set(&opts, "fflags", "nobuffer", 0);
    av_dict_set(&opts, "flags", "low_delay", 0);

    int ret = avformat_open_input(&ctx->fmt_ctx, rtsp_url, nullptr, &opts);
    av_dict_free(&opts);
    env->ReleaseStringUTFChars(url, rtsp_url);

    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        __android_log_print(ANDROID_LOG_ERROR, "VissoraFFmpeg", "avformat_open_input failed (%d): %s", ret, errbuf);
        destroyPlayer(ctx);
        return 0;
    }

    ret = avformat_find_stream_info(ctx->fmt_ctx, nullptr);
    if (ret < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "VissoraFFmpeg", "avformat_find_stream_info failed: %d", ret);
        destroyPlayer(ctx);
        return 0;
    }

    const AVCodec* codec = nullptr;
    ctx->video_stream_idx = av_find_best_stream(ctx->fmt_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);

    if (ctx->video_stream_idx < 0 || codec == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "VissoraFFmpeg", "No video stream found");
        destroyPlayer(ctx);
        return 0;
    }

    ctx->codec_ctx = avcodec_alloc_context3(codec);
    if (ctx->codec_ctx == nullptr) {
        destroyPlayer(ctx);
        return 0;
    }

    ret = avcodec_parameters_to_context(ctx->codec_ctx, ctx->fmt_ctx->streams[ctx->video_stream_idx]->codecpar);
    if (ret < 0) {
        destroyPlayer(ctx);
        return 0;
    }

    ret = avcodec_open2(ctx->codec_ctx, codec, nullptr);
    if (ret < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "VissoraFFmpeg", "avcodec_open2 failed: %d", ret);
        destroyPlayer(ctx);
        return 0;
    }

    ctx->codec_ctx->thread_count = 0;
    ctx->codec_ctx->thread_type = FF_THREAD_FRAME;

    ctx->width = ctx->codec_ctx->width;
    ctx->height = ctx->codec_ctx->height;

    if (ctx->width <= 0 || ctx->height <= 0) {
        destroyPlayer(ctx);
        return 0;
    }

    ctx->raw_frame = av_frame_alloc();
    if (ctx->raw_frame == nullptr) {
        destroyPlayer(ctx);
        return 0;
    }

    ctx->sws_ctx = sws_getContext(ctx->width, ctx->height, ctx->codec_ctx->pix_fmt, ctx->width, ctx->height, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (ctx->sws_ctx == nullptr) {
        destroyPlayer(ctx);
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jint JNICALL Java_org_anvlabs_vissora_VideoStream_decodeNextFrame(JNIEnv* env, jobject thiz, jlong handle, jobject direct_buffer, jstring ePath, jstring asset) {
    auto* ctx = reinterpret_cast<DesktopPlayerCtx*>(handle);
    if (!ctx) return -1;
    if (!direct_buffer) return -2;

    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(direct_buffer));
    if (!dst) return -2;

    const char* emb_path = env->GetStringUTFChars(ePath, nullptr);
    std::string safe_emb_path(emb_path);
    env->ReleaseStringUTFChars(ePath, emb_path);

    const char* asset_path = env->GetStringUTFChars(asset, nullptr);
    std::string assetPath(asset_path);
    env->ReleaseStringUTFChars(asset, asset_path);

    const jlong capacity = env->GetDirectBufferCapacity(direct_buffer);
    const int dst_stride = ctx->width * 4;
    const jlong required_size = static_cast<jlong>(dst_stride) * ctx->height;
    if (capacity < required_size) return -3;

    uint8_t* dst_data[4] = {dst, nullptr, nullptr, nullptr};
    int dst_linesizes[4] = {dst_stride, 0, 0, 0};

    AVPacket* pkt = av_packet_alloc();
    if (!pkt) return -4;

    int result = -1;
    while (av_read_frame(ctx->fmt_ctx, pkt) >= 0) {
        if (pkt->stream_index == ctx->video_stream_idx) {
            int ret = avcodec_send_packet(ctx->codec_ctx, pkt);
            if (ret == 0) {
                ret = avcodec_receive_frame(ctx->codec_ctx, ctx->raw_frame);
                if (ret == 0) {
                    sws_scale(ctx->sws_ctx, ctx->raw_frame->data, ctx->raw_frame->linesize, 0, ctx->height, dst_data, dst_linesizes);
                    if(ctx->yolo){
                        drawYolo(ctx->name, dst, ctx->width, ctx->height, 4, ncnn::Mat::PIXEL_RGBA2RGB, ctx->classes, assetPath);
                        ctx->yolo = false;
                    }
                    const int width = ctx->width;
                    const int height = ctx->height;
                    const int size = std::max(width, height);
                    const size_t frame_bytes = static_cast<size_t>(width) * height * 4;

                    auto inference_frame = std::make_shared<std::vector<uint8_t>>(
                        dst, dst + frame_bytes
                    );
                    
                    if(!ctx->running.load()){
                        ctx->running.store(true);
                        std::thread lambdaThread(
                            [inference_frame, width, height, size, safe_emb_path, ctx]() {
                                unsigned char* letter_box = construct_letterbox(inference_frame->data(), width, height, 4);
                                if (!letter_box){
                                    ctx->running.store(false);
                                    return;
                                }

                                std::vector<FaceDetection> det = trackFaces(letter_box, size, size, ncnn::Mat::PIXEL_RGBA2BGR, 0.5f);
                                std::vector<byte_track::Object> objects;
                                objects.reserve(det.size());
                                for (const auto face : det) {
                                    float px1 = (face.x1 * size) - (size - width) / 2;
                                    float py1 = (face.y1 * size) - (size - height) / 2;
                                    float px2 = (face.x2 * size) - (size - width) / 2;
                                    float py2 = (face.y2 * size) - (size - height) / 2;

                                    float box_w = px2 - px1;
                                    float box_h = py2 - py1;

                                    byte_track::Rect<float> rect(px1, py1, box_w, box_h);
                                    objects.emplace_back(rect, 0, face.score, face.landmarks);
                                }
                                
                                auto tracks = ctx->tracker.update(objects);
                                std::map<size_t, std::string> tId = *std::atomic_load(&ctx->trackIds);
                                
                                for (const auto& track : tracks) {
                                    if(tId.find(track->getTrackId()) == tId.end() && track->getScore() >= 0.85f) {
                                        tId.insert({track->getTrackId(), ""});
                                        const auto& rect = track->getRect();
                                        int cw = 0, ch = 0;
                                        unsigned char* cropped_face = crop_image(inference_frame->data(), width, height, 4, rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height(), &cw, &ch);
                                        
                                        FaceDetection best_match;
                                        float max_iou = 0.0f;
                                        
                                        for (const auto& face : det) {
                                            float px1 = (face.x1 * size) - (size - width) / 2;
                                            float py1 = (face.y1 * size) - (size - height) / 2;
                                            float box_w = ((face.x2 * size) - (size - width) / 2) - px1;
                                            float box_h = ((face.y2 * size) - (size - height) / 2) - py1;
                                            
                                            float ix = std::max(rect.x(), px1);
                                            float iy = std::max(rect.y(), py1);
                                            float ix2 = std::min(rect.x() + rect.width(), px1 + box_w);
                                            float iy2 = std::min(rect.y() + rect.height(), py1 + box_h);
                                            
                                            float iw = std::max(0.0f, ix2 - ix);
                                            float ih = std::max(0.0f, iy2 - iy);
                                            float inter = iw * ih;
                                            float uni = rect.width() * rect.height() + box_w * box_h - inter;
                                            float iou = uni > 0 ? inter / uni : 0.0f;
                                            
                                            if (iou > max_iou) {
                                                max_iou = iou;
                                                best_match = face;
                                            }
                                        }

                                        if (max_iou > 0.3f) {
                                            float src_landmarks[5][2];
                                            for (int i = 0; i < 5; i++) {
                                                src_landmarks[i][0] = (best_match.landmarks[i][0] * size) - (size - width) / 2.0f;
                                                src_landmarks[i][1] = (best_match.landmarks[i][1] * size) - (size - height) / 2.0f;
                                            }

                                            Affine2D invMatrix = getSimilarityTransform(src_landmarks, TARGET_LANDMARKS);
                                            std::vector<uint8_t> aligned_face(112 * 112 * 4);
                                            warpFaceBilinear(inference_frame->data(), width, height, 4, invMatrix, aligned_face.data());
                                            std::vector<float> emb1 = genEmb(aligned_face.data(), 112, 112, 4);
                                            struct stat sb;
                                            for (const auto& entry : fs::directory_iterator(safe_emb_path)) {
                                                fs::path outfilename = entry.path();
                                                std::string outfilename_str = outfilename.string();
                                                const char* path = outfilename_str.c_str();
                                                if (stat(path, &sb) == 0 && !(sb.st_mode & S_IFDIR)){
                                                    std::ifstream file(path);
                                                    if (file.is_open()) {
                                                        std::stringstream buffer;
                                                        buffer << file.rdbuf();
                                                        std::string content = buffer.str();

                                                        std::stringstream ss(content);
                                                        std::string word;
                                                        float sum = 0;
                                                        uint16_t i = 0;
                                                        while (ss >> word && i < 512) {
                                                            sum += std::stof(word) * emb1[i++];
                                                        }
                                                        if(sum > 0.0f){
                                                            tId[track->getTrackId()] = outfilename.filename().string();
                                                            break;
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                std::shared_ptr<std::vector<byte_track::BYTETracker::STrackPtr>> snapshot = std::make_shared<std::vector<byte_track::BYTETracker::STrackPtr>>(std::move(tracks));
                                std::shared_ptr<std::map<size_t, std::string>> snapshot2 = std::make_shared<std::map<size_t, std::string>>(std::move(tId));
                                
                                std::atomic_store(&ctx->res, std::move(snapshot));
                                std::atomic_store(&ctx->trackIds, std::move(snapshot2));
                                
                                std::free(letter_box);
                                ctx->running.store(false);
                            }
                        );
                        lambdaThread.detach();
                    }
                    
                    std::vector<byte_track::BYTETracker::STrackPtr> snapshot = *std::atomic_load(&ctx->res);
                    std::map<size_t, std::string> snapshot2 = *std::atomic_load(&ctx->trackIds);
                    
                    for (const auto& track : snapshot) {
                        if(track->getScore() > 0.5f){
                            const auto& rect = track->getRect();
                            
                            int px1 = static_cast<int>(rect.x());
                            int py1 = static_cast<int>(rect.y());
                            int px2 = static_cast<int>(rect.x() + rect.width());
                            int py2 = static_cast<int>(rect.y() + rect.height());
                            
                            bool recz = false;
                            if(snapshot2.find(track->getTrackId()) != snapshot2.end()) {
                                std::string name = snapshot2[track->getTrackId()];
                                if(name != ""){
                                    ncnn::draw_text_c4(dst, ctx->width, ctx->height, name.c_str(), px1, py1, 16, 0xFF00FF00);
                                    recz = true;
                                }
                            }
                            if(!recz){
                                ncnn::draw_rectangle_c4(dst, ctx->width, ctx->height, px1, py1, px2 - px1, py2 - py1, 0xFF0000FF, 5);
                            }
                            else{
                                ncnn::draw_rectangle_c4(dst, ctx->width, ctx->height, px1, py1, px2 - px1, py2 - py1, 0xFF00FF00, 5);
                            }
                        }
                    }
                    result = 0;
                    av_packet_unref(pkt);
                    break;
                }
            }

        }
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);
    return result;
}

JNIEXPORT jint JNICALL Java_org_anvlabs_vissora_VideoStream_getWidth(JNIEnv* env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<DesktopPlayerCtx*>(handle);
    return ctx ? ctx->width : 0;
}

JNIEXPORT jint JNICALL Java_org_anvlabs_vissora_VideoStream_getHeight(JNIEnv* env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<DesktopPlayerCtx*>(handle);
    return ctx ? ctx->height : 0;
}

JNIEXPORT void JNICALL Java_org_anvlabs_vissora_VideoStream_closeStream(JNIEnv* env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<DesktopPlayerCtx*>(handle);
    destroyPlayer(ctx);
}

}