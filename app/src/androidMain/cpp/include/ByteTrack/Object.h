#pragma once

#include "ByteTrack/Rect.h"

namespace byte_track
{
struct Object
{
    Rect<float> rect;
    int label;
    float prob;
    float landmarks[5][2];

    Object(const Rect<float> &_rect,
           const int &_label,
           const float &_prob, const float landmarks[5][2]);
};
}