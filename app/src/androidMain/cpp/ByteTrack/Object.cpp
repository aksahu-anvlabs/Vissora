#include "ByteTrack/Object.h"

byte_track::Object::Object(const Rect<float> &_rect,
                           const int &_label,
                           const float &_prob, const float _landmarks[5][2]) : rect(_rect), label(_label), prob(_prob)
{
    for (int i = 0; i < 5; ++i) {
        landmarks[i][0] = _landmarks[i][0];
        landmarks[i][1] = _landmarks[i][1];
    }
}