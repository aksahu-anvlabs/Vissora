#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_RESIZE_IMPLEMENTATION
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define NOMINMAX

#include <jni.h>
#include <chrono>
#include <algorithm>
#include <cstdio>
#include <stdlib.h>
#include <string.h>
#include <cmath>
#include <fstream>
#include <iostream>
#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include "global.h"
#include "stb_image.h"
#include "stb_image_resize2.h"
#include "stb_image_write.h"

#define TAG "ncnn"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

ncnn::Net arcNet, scrfdNet, yoloNet;
std::chrono::high_resolution_clock::time_point start, end;
std::chrono::milliseconds duration;

struct ExoPlayer{
    const char* cam;
    uint16_t width;
    uint16_t height;
    uint8_t channels;
    bool yolo;
};

const float mean_vals[3] = {127.5f, 127.5f, 127.5f};
const float norm_vals[3] = {1/128.0f, 1/128.0f, 1/128.0f};

std::vector<float> genEmb(uint8_t* faceImg, uint16_t cw, uint16_t ch, int channels){
    int pixel_format = ncnn::Mat::PIXEL_BGR;
    
    // Dynamically handle RGBA from video stream and RGB from stbi_load
    if (channels == 4) {
        pixel_format = ncnn::Mat::PIXEL_RGBA2BGR;
    } else if (channels == 3) {
        pixel_format = ncnn::Mat::PIXEL_RGB2BGR;
    }

    ncnn::Mat faceIn = ncnn::Mat::from_pixels(faceImg, pixel_format, cw, ch);
    faceIn.substract_mean_normalize(mean_vals, norm_vals);
    
    ncnn::Mat out;
    ncnn::Extractor ex = arcNet.create_extractor();
    ex.input("in0", faceIn);
    ex.extract("out0", out);

    std::vector<float> emb(512);
    memcpy(emb.data(), out.row(0), 512 * sizeof(float));
    float sum = 0.0f;
    for (int i = 0; i < 512; i++) {
        sum += emb[i] * emb[i];
    }
    float norm = std::max(std::sqrt(sum), 1e-10f);
    for (int i = 0; i < 512; i++) {
        emb[i] /= norm;
    }

    return emb;
}

std::vector<Detection> parseYoloV8(const ncnn::Mat& out, float threshold) {
    std::vector<Detection> detections;
    
    int num_anchors = out.w; // 8400
    int num_features = out.h; // 605
    int num_classes = num_features - 4; // 601

    for (int i = 0; i < num_anchors; i++) {
        float max_score = 0.0f;
        int class_index = -1;

        // 1. Find the highest class score for this anchor
        // Starting at row 4, iterating through all class probabilities
        for (int c = 0; c < num_classes; c++) {
            float score = out.row(4 + c)[i];
            if (score > max_score) {
                max_score = score;
                class_index = c;
            }
        }

        // 2. If the score is above threshold, decode the bounding box
        if (max_score > threshold) {
            // YOLOv8 outputs Center X, Center Y, Width, Height
            float cx = out.row(0)[i];
            float cy = out.row(1)[i];
            float bw = out.row(2)[i];
            float bh = out.row(3)[i];

            // Convert to Top-Left (x1, y1) and Bottom-Right (x2, y2)
            float x1 = cx - bw / 2.0f;
            float y1 = cy - bh / 2.0f;
            float x2 = cx + bw / 2.0f;
            float y2 = cy + bh / 2.0f;

            // Create a detection object and normalize coordinates to [0.0, 1.0] 
            // assuming your NCNN input tensor was resized to 640x640
            Detection det;
            det.x1 = x1 / 640.0f;
            det.y1 = y1 / 640.0f;
            det.x2 = x2 / 640.0f;
            det.y2 = y2 / 640.0f;
            det.confidence = max_score;
            det.class_id = class_index;
            
            detections.push_back(det);
        }
    }

    // You will need to pass 'detections' through NMS (Non-Maximum Suppression) next!
    // final_detections = nms(detections, 0.45f);
    
    return detections;
}

void drawYolo(const char* name, unsigned char* img, int width, int height, int channels, int mode, std::vector<int> classes, std::string assetPath){
    int size;
    unsigned char* letter_box = construct_letterbox(img, width, height, channels);
    size = std::max(width, height);
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(letter_box, mode, size, size, 640, 640), out;
    in.substract_mean_normalize(mean_vals, norm_vals);
    
    ncnn::Extractor ex = yoloNet.create_extractor();
    ex.input("in0", in);
    ex.extract("out0", out);
    printf("%d %d %d\n", out.w, out.h, out.c);
    std::vector<Detection> detections = parseYoloV8(out, 0.3f);
    std::vector<Detection> final_detections = nms(detections, 0.3f);
    uint16_t cnt = 0;
    for(Detection det : final_detections){
        if(count(classes.begin(), classes.end(), det.class_id) > 0){
            cnt += 1;
            float px1 = (det.x1 * size) - (size - width) / 2;
            float py1 = (det.y1 * size) - (size - height) / 2;
            float px2 = (det.x2 * size) - (size - width) / 2;
            float py2 = (det.y2 * size) - (size - height) / 2;
            //printf("%f %f %f %f\n", px1, py1, px2, py2);
            ncnn::draw_rectangle_c4(img, width, height, px1, py1, px2 - px1, py2 - py1, 0xFF0000FF, 5);
            ncnn::draw_text_c4(img, width, height, open_images_v7_classes[det.class_id].c_str(), px1, py1, 16, 0xFF0000FF);
        }
    }
    //printf("%s\n", (std::string(name) + std::string(".jpg")).c_str());
    stbi_write_jpg((assetPath + std::string("/images/") + std::string(name) + std::string("@") + std::to_string(cnt)).c_str(), width, height, channels, img, 100);
    fflush(stdout);
}

std::vector<FaceDetection> trackFaces(unsigned char* img, int width, int height, int mode, float threshold){
    std::vector<FaceDetection> detections;
    ncnn::Mat scores[3], bboxes[3], kpss[3];
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(img, mode, width, height, 640, 640);
    in.substract_mean_normalize(mean_vals, norm_vals);
    ncnn::Extractor ex = scrfdNet.create_extractor();

    ex.input("input.1", in);

    //start = std::chrono::high_resolution_clock::now();
    ex.extract("score_8", scores[0]); ex.extract("score_16", scores[1]); ex.extract("score_32", scores[2]);
    ex.extract("bbox_8", bboxes[0]); ex.extract("bbox_16", bboxes[1]); ex.extract("bbox_32", bboxes[2]);
    ex.extract("kps_8", kpss[0]); ex.extract("kps_16", kpss[1]); ex.extract("kps_32", kpss[2]);
    //end = std::chrono::high_resolution_clock::now();
    //duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
    //uint32_t inference_time = duration.count();
    //std::cout << "Infernce: " << inference_time << std::endl;
    const int strides[3] = {8, 16, 32};
    detections.clear();

    for (int k = 0; k < 3; k++) {
        const int stride = strides[k];

        for (int y = 0; y < scores[k].h; y++) {
            for (int x = 0; x < scores[k].w; x++) {

                float score0 = scores[k].channel(0).row(y)[x];
                float score1 = scores[k].channel(1).row(y)[x];

                int anchor = -1;
                float score = 0.f;

                if (score0 > threshold && score0 >= score1) {
                    anchor = 0;
                    score = score0;
                } else if (score1 > threshold) {
                    anchor = 1;
                    score = score1;
                }

                if (anchor == -1)
                    continue;

                float* bbox[4];

                for (int i = 0; i < 4; i++) {
                    bbox[i] = bboxes[k].channel(anchor * 4 + i).row(y);
                }

                float left   = bbox[0][x] * stride;
                float top    = bbox[1][x] * stride;
                float right  = bbox[2][x] * stride;
                float bottom = bbox[3][x] * stride;

                // Anchor center in the 640x640 input image.
                float cx = x * stride;
                float cy = y * stride;

                // Decode box in input-image pixel coordinates.
                float x1 = cx - left;
                float y1 = cy - top;
                float x2 = cx + right;
                float y2 = cy + bottom;
                float landmarks[5][2];

                for (int p = 0; p < 5; p++) {
                    float kpx = cx + kpss[k].channel(anchor * 10 + p * 2).row(y)[x] * stride;
                    float kpy = cy + kpss[k].channel(anchor * 10 + p * 2 + 1).row(y)[x] * stride;
                    landmarks[p][0] = kpx / 640.0f;
                    landmarks[p][1] = kpy / 640.0f;
                }

                FaceDetection det;
                det.x1 = x1/640.0f; det.x2 = x2/640.0f; det.y1 = y1/640.0f; det.y2 = y2/640.0f;
                det.score = score;
                memcpy(det.landmarks, landmarks, sizeof(float) * 10);
                detections.push_back(det);
            }
        }
    }
    std::vector<FaceDetection> finalFaces = nms(detections, 0.4f);
    return finalFaces;
}

extern "C"{
// JNIEXPORT jfloatArray JNICALL
// Java_org_anvlabs_vissora_NExecutor_getBoxes(JNIEnv* env, jobject thiz, jint width, jint height, jbyteArray data){

// }

JNIEXPORT jlong JNICALL
Java_org_anvlabs_vissora_VideoStream_registerStream(JNIEnv* env, jobject thiz, jstring name, jint width, jint height, jint channels){
    const char* cam = env->GetStringUTFChars(name, nullptr);
    auto* ctx = new (std::nothrow) ExoPlayer{cam, (uint16_t)width, (uint16_t)height, (uint8_t)channels, false};
    env->ReleaseStringUTFChars(name, cam);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL Java_org_anvlabs_vissora_VideoStream_scanFaces(JNIEnv* env, jobject thiz, jlong handle, jobject direct_buffer, jstring ePath) {
    const char* embPath = env->GetStringUTFChars(ePath, nullptr);
    auto* ctx = reinterpret_cast<ExoPlayer*>(handle);

    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(direct_buffer));
    if (!dst) return false;
    
    ncnn::Mat in = ncnn::Mat::from_pixels(dst, ncnn::Mat::PIXEL_RGBA2RGB, ctx->width, ctx->height);
    env->ReleaseStringUTFChars(ePath, embPath);
    return true;
}

JNIEXPORT jstring JNICALL
Java_org_anvlabs_vissora_MainActivity_setupModel(JNIEnv* env, jobject thiz, jstring modelPath){
	const char* path = env->GetStringUTFChars(modelPath, nullptr);
	const std::string modelPaths(path);
	try {
		const std::string arcParamPath = modelPaths + "/arcface/model.ncnn.param";
        const std::string arcModelPath = modelPaths + "/arcface/model.ncnn.bin";

        const std::string scrfdParamPath = modelPaths + "/scrfd/model.ncnn.param";
        const std::string scrfdModelPath = modelPaths + "/scrfd/model.ncnn.bin";

        const std::string yoloParamPath = modelPaths + "/yolov8/model.ncnn.param";
        const std::string yoloModelPath = modelPaths + "/yolov8/model.ncnn.bin";
        
        // ncnn::create_gpu_instance();
        // int gpu_count = ncnn::get_gpu_count();
        // if (gpu_count > 0) {
        //     // arcNet.opt.use_vulkan_compute = true;
        //     // arcNet.opt.use_fp16_packed = true;
        //     // arcNet.opt.use_fp16_storage = true;
        //     // arcNet.opt.use_fp16_arithmetic = true;
        //     // arcNet.opt.use_int8_inference = true;
        //     scrfdNet.set_vulkan_device(0);
        // }

        LOGD("%s\n", arcParamPath.c_str());
        int paramRet = arcNet.load_param(arcParamPath.c_str());
		std::cout << "load_param: " << paramRet << '\n';

		if (paramRet != 0) {
		    return env->NewStringUTF("Failed to load arc NCNN param file");
		}

		int modelRet = arcNet.load_model(arcModelPath.c_str());
		std::cout << "load_model: " << modelRet << '\n';

		if (modelRet != 0) {
		    return env->NewStringUTF("Failed to load arc NCNN model weights");
		}

        paramRet = scrfdNet.load_param(scrfdParamPath.c_str());
        std::cout << "load_param: " << paramRet << '\n';

        if (paramRet != 0) {
            return env->NewStringUTF("Failed to load scrfd NCNN param file");
        }

        modelRet = scrfdNet.load_model(scrfdModelPath.c_str());
        std::cout << "load_model: " << modelRet << '\n';

        if (modelRet != 0) {
            return env->NewStringUTF("Failed to load scrfd NCNN model weights");
        }

        paramRet = yoloNet.load_param(yoloParamPath.c_str());
        std::cout << "load_param: " << paramRet << '\n';

        if (paramRet != 0) {
            return env->NewStringUTF("Failed to load scrfd NCNN param file");
        }

        modelRet = yoloNet.load_model(yoloModelPath.c_str());
        std::cout << "load_model: " << modelRet << '\n';

        if (modelRet != 0) {
            return env->NewStringUTF("Failed to load scrfd NCNN model weights");
        }

        //scrfdNet.opt.use_vulkan_compute = true;
        scrfdNet.opt.use_fp16_packed = true;
        scrfdNet.opt.use_fp16_storage = true;
        scrfdNet.opt.use_fp16_arithmetic = true;

    }
    catch (const std::runtime_error &e) {
        __android_log_print(ANDROID_LOG_ERROR, "VissoraFFmpeg", "%s", e.what());
        return env->NewStringUTF(e.what());
    }
    catch (...) {
        return env->NewStringUTF("Unknown exception caught!");
    }

    env->ReleaseStringUTFChars(modelPath, path);
    return env->NewStringUTF("Model ready!");
}

JNIEXPORT jint JNICALL
Java_org_anvlabs_vissora_NExecutor_genEmb(JNIEnv* env, jobject thiz, jstring path, jstring outpath){
	int width, height, channels;
	const char* imgPath = env->GetStringUTFChars(path, nullptr);
	const char* outPath = env->GetStringUTFChars(outpath, nullptr);

    unsigned char *img = stbi_load(imgPath, &width, &height, &channels, 3);
    if(img == NULL) {
		printf("Error in loading the image\n");
        return -1;
    }

    unsigned char* letter_box = construct_letterbox(img, width, height, 3);
    uint16_t size = std::max(width, height);

    unsigned char *resized_data = (unsigned char *)malloc(640 * 640 * 3);
    stbir_resize_uint8_srgb(
        letter_box, size, size, size * 3,
        resized_data, 640, 640, 640 * 3,
        (stbir_pixel_layout)STBIR_RGB
    );

    std::vector<FaceDetection> finalFaces = trackFaces(letter_box, size, size, ncnn::Mat::PIXEL_RGB2BGR, 0.5f);

    // stbi_write_jpg("output.jpg", 640, 640, 3, resized_data, 100);
    std::vector<uint8_t> aligned_face(112 * 112 * 3);
    bool face_found = false;

    for (const auto& face : finalFaces) {
        if(face.score > 0.7f){
            float src_landmarks[5][2];
            for (int i = 0; i < 5; i++) {
                // Scale normalized landmarks up to the letterbox size, then remove padding
                src_landmarks[i][0] = (face.landmarks[i][0] * size) - (size - width) / 2.0f;
                src_landmarks[i][1] = (face.landmarks[i][1] * size) - (size - height) / 2.0f;
            }
            
            Affine2D invMatrix = getSimilarityTransform(src_landmarks, TARGET_LANDMARKS);
            warpFaceBilinear(img, width, height, 3, invMatrix, aligned_face.data());
            
            face_found = true;
            break;
        }
    }

    if(face_found){
        std::ofstream outfile(outPath);
        std::vector<float> row = genEmb(aligned_face.data(), 112, 112, 3);
        for (int i = 0; i < 512; ++i) {
            outfile << row[i] << " ";
        }
        std::cout << "Embedding saved" << std::endl;
    }

    stbi_image_free(resized_data);
    stbi_image_free(letter_box);
    stbi_image_free(img);
    env->ReleaseStringUTFChars(path, imgPath);
    env->ReleaseStringUTFChars(outpath, outPath);

    return 0;
}
}