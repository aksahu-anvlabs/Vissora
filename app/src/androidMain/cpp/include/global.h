#pragma once
#include <string>
#include <vector>

struct FaceDetection {
    float x1, y1, x2, y2;
    float score;
    float landmarks[5][2];
};

struct Detection{
    float x1, x2, y1, y2, confidence;
    uint16_t class_id;
};


const float TARGET_LANDMARKS[5][2] = {
    {38.2946f, 51.6963f}, // Left eye
    {73.5318f, 51.5014f}, // Right eye
    {56.0252f, 71.7366f}, // Nose
    {41.5493f, 92.3655f}, // Left mouth corner
    {70.7299f, 92.2041f}  // Right mouth corner
};

struct Affine2D {
    float a, b, tx, ty;
};

Affine2D getSimilarityTransform(const float src[5][2], const float dst[5][2]);
void warpFaceBilinear(const uint8_t* src_pixels, int src_w, int src_h, int channels, const Affine2D& inv, uint8_t* dst_bgr);
std::vector<FaceDetection> trackFaces(unsigned char* img, int width, int height, int mode, float threshold);
unsigned char* construct_letterbox(unsigned char* img, uint16_t width, uint16_t height, uint8_t n_channel);
std::vector<float> genEmb(unsigned char* face, uint16_t cw, uint16_t ch, int channels);
unsigned char* crop_image(const unsigned char* src_pixels, int src_w, int src_h, int channels, int x1, int y1, int x2, int y2, int* out_w, int* out_h);
void drawYolo(const char* name, unsigned char* img, int width, int height, int channels, int mode, std::vector<int> classes, std::string assetPath);
std::vector<FaceDetection> nms(std::vector<FaceDetection>& detections, float iouThreshold);
float calculate_iou(const Detection& a, const Detection& b);
std::vector<Detection> nms(std::vector<Detection>& detections, float iouThreshold);

extern const std::vector<std::string> open_images_v7_classes;