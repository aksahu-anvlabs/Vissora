#define NOMINMAX

#include <jni.h>
#include <chrono>
#include <algorithm>
#include <cstdio>
#include <stdlib.h>
#include <string.h>
#include <fstream>
#include <iostream>
#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include <cmath>
#include <algorithm>
#include "global.h"
#include "stb_image.h"
#include "stb_image_resize2.h"
#include "stb_image_write.h"

Affine2D getSimilarityTransform(const float src[5][2], const float dst[5][2]) {
    float mean_src_x = 0.0f, mean_src_y = 0.0f;
    float mean_dst_x = 0.0f, mean_dst_y = 0.0f;

    for (int i = 0; i < 5; i++) {
        mean_src_x += src[i][0];
        mean_src_y += src[i][1];
        mean_dst_x += dst[i][0];
        mean_dst_y += dst[i][1];
    }
    mean_src_x /= 5.0f; mean_src_y /= 5.0f;
    mean_dst_x /= 5.0f; mean_dst_y /= 5.0f;

    float num_a = 0.0f, num_b = 0.0f, den = 0.0f;
    for (int i = 0; i < 5; i++) {
        float dx_dst = dst[i][0] - mean_dst_x;
        float dy_dst = dst[i][1] - mean_dst_y;
        float dx_src = src[i][0] - mean_src_x;
        float dy_src = src[i][1] - mean_src_y;

        num_a += dx_dst * dx_src + dy_dst * dy_src;
        num_b += dx_dst * dy_src - dy_dst * dx_src;
        den   += dx_dst * dx_dst + dy_dst * dy_dst;
    }

    Affine2D inv;
    inv.a = num_a / den;
    inv.b = num_b / den;
    inv.tx = mean_src_x - (inv.a * mean_dst_x - inv.b * mean_dst_y);
    inv.ty = mean_src_y - (inv.b * mean_dst_x + inv.a * mean_dst_y);
    return inv;
}

void warpFaceBilinear(const uint8_t* src_pixels, int src_w, int src_h, int channels, const Affine2D& inv, uint8_t* dst_bgr) {
    for (int dy = 0; dy < 112; dy++) {
        for (int dx = 0; dx < 112; dx++) {
            // Map destination pixel (dx, dy) back to source pixel coordinates
            float sx = inv.a * dx - inv.b * dy + inv.tx;
            float sy = inv.b * dx + inv.a * dy + inv.ty;

            int x0 = static_cast<int>(std::floor(sx));
            int y0 = static_cast<int>(std::floor(sy));
            int x1 = x0 + 1;
            int y1 = y0 + 1;

            float fx = sx - x0;
            float fy = sy - y0;

            float w00 = (1.0f - fx) * (1.0f - fy);
            float w01 = fx * (1.0f - fy);
            float w10 = (1.0f - fx) * fy;
            float w11 = fx * fy;

            auto sample_pixel = [&](int px, int py, int c) -> float {
                if (px < 0 || px >= src_w || py < 0 || py >= src_h) return 0.0f;
                return src_pixels[(py * src_w + px) * channels + c];
            };

            int out_idx = (dy * 112 + dx) * 3;

            // Output format: BGR (ArcFace input standard)
            int channel_order[3] = {2, 1, 0}; // If source is RGBA: B=2, G=1, R=0
            if (channels == 3) {
                // If source is RGB: B=2, G=1, R=0
                channel_order[0] = 2; channel_order[1] = 1; channel_order[2] = 0;
            }

            for (int c = 0; c < 3; c++) {
                int sc = channel_order[c];
                float val = w00 * sample_pixel(x0, y0, sc) + w01 * sample_pixel(x1, y0, sc) + w10 * sample_pixel(x0, y1, sc) + w11 * sample_pixel(x1, y1, sc);
                dst_bgr[out_idx + c] = static_cast<uint8_t>(std::clamp(val, 0.0f, 255.0f));
            }
        }
    }
}

float calculate_iou(const FaceDetection& a, const FaceDetection& b) {
    float ix1 = std::max(a.x1, b.x1);
    float iy1 = std::max(a.y1, b.y1);
    float ix2 = std::min(a.x2, b.x2);
    float iy2 = std::min(a.y2, b.y2);

    float iw = std::max(0.0f, ix2 - ix1);
    float ih = std::max(0.0f, iy2 - iy1);
    float intersection = iw * ih;

    float areaA = std::max(0.0f, a.x2 - a.x1) *
                  std::max(0.0f, a.y2 - a.y1);
    float areaB = std::max(0.0f, b.x2 - b.x1) *
                  std::max(0.0f, b.y2 - b.y1);

    float unionArea = areaA + areaB - intersection;

    return unionArea > 0.0f ? intersection / unionArea : 0.0f;
}

std::vector<FaceDetection> nms(std::vector<FaceDetection>& detections, float iouThreshold = 0.4f){
    std::sort(
        detections.begin(), detections.end(),
        [](const FaceDetection& a, const FaceDetection& b) {
            return a.score > b.score;
        }
    );

    std::vector<FaceDetection> kept;
    std::vector<bool> suppressed(detections.size(), false);

    for (size_t i = 0; i < detections.size(); ++i) {
        if (suppressed[i])
            continue;

        kept.push_back(detections[i]);

        for (size_t j = i + 1; j < detections.size(); ++j) {
            if (!suppressed[j] &&
                calculate_iou(detections[i], detections[j]) > iouThreshold) {
                suppressed[j] = true;
            }
        }
    }

    return kept;
}

unsigned char* construct_letterbox(unsigned char* img, uint16_t width, uint16_t height, uint8_t n_channel){
    int size = std::max(width, height);

    int padLeft = (size - width) / 2;
    int padTop  = (size - height) / 2;

    unsigned char* letter_box = (unsigned char*)calloc(size * size * n_channel, 1);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int src = (y * width + x) * n_channel;
            int dst = ((y + padTop) * size + (x + padLeft)) * n_channel;
            for(int k = 0; k < n_channel; k++){
                letter_box[dst + k] = img[src + k];
            }
        }
    }

    return letter_box;
}

unsigned char* crop_image(const unsigned char* src_pixels, int src_w, int src_h, int channels, int x1, int y1, int x2, int y2, int* out_w, int* out_h) {
    if (x1 < 0) x1 = 0;
    if (y1 < 0) y1 = 0;
    if (x2 > src_w) x2 = src_w;
    if (y2 > src_h) y2 = src_h;

    int crop_w = x2 - x1;
    int crop_h = y2 - y1;

    if (crop_w <= 0 || crop_h <= 0) {
        *out_w = 0;
        *out_h = 0;
        return NULL;
    }

    size_t row_bytes = (size_t)crop_w * channels;
    unsigned char* cropped = (unsigned char*)malloc(row_bytes * crop_h);
    if (!cropped) return NULL;

    for (int y = 0; y < crop_h; ++y) {
        size_t src_offset = (((size_t)(y1 + y) * src_w) + x1) * channels;
        size_t dst_offset = ((size_t)y * crop_w) * channels;

        memcpy(cropped + dst_offset, src_pixels + src_offset, row_bytes);
    }

    *out_w = crop_w;
    *out_h = crop_h;
    return cropped;
}

float calculate_iou(const Detection& a, const Detection& b) {
    float ix1 = std::max(a.x1, b.x1);
    float iy1 = std::max(a.y1, b.y1);
    float ix2 = std::min(a.x2, b.x2);
    float iy2 = std::min(a.y2, b.y2);

    float iw = std::max(0.0f, ix2 - ix1);
    float ih = std::max(0.0f, iy2 - iy1);
    float intersection = iw * ih;

    float areaA = std::max(0.0f, a.x2 - a.x1) * std::max(0.0f, a.y2 - a.y1);
    float areaB = std::max(0.0f, b.x2 - b.x1) * std::max(0.0f, b.y2 - b.y1);

    float unionArea = areaA + areaB - intersection;
    return unionArea > 0.0f ? intersection / unionArea : 0.0f;
}

// Overloaded NMS for YOLO Detection struct
std::vector<Detection> nms(std::vector<Detection>& detections, float iouThreshold = 0.45f) {
    std::sort(
        detections.begin(), detections.end(),
        [](const Detection& a, const Detection& b) {
            return a.confidence > b.confidence;
        }
    );

    std::vector<Detection> kept;
    std::vector<bool> suppressed(detections.size(), false);

    for (size_t i = 0; i < detections.size(); ++i) {
        if (suppressed[i])
            continue;

        kept.push_back(detections[i]);

        for (size_t j = i + 1; j < detections.size(); ++j) {
            // Only suppress if the bounding boxes overlap heavily AND they are the same class
            if (!suppressed[j] && detections[i].class_id == detections[j].class_id &&
                calculate_iou(detections[i], detections[j]) > iouThreshold) {
                suppressed[j] = true;
            }
        }
    }

    return kept;
}

const std::vector<std::string> open_images_v7_classes = {
    "Accordion", "Adhesive tape", "Aircraft", "Airplane", "Alarm clock",
    "Alpaca", "Ambulance", "Animal", "Ant", "Antelope", "Apple",
    "Armadillo", "Artichoke", "Auto part", "Axe", "Backpack", "Bagel",
    "Baked goods", "Balance beam", "Ball", "Balloon", "Banana",
    "Band-aid", "Banjo", "Barge", "Barrel", "Baseball bat",
    "Baseball glove", "Bat (Animal)", "Bathroom accessory",
    "Bathroom cabinet", "Bathtub", "Beaker", "Bear", "Bed", "Bee",
    "Beehive", "Beer", "Beetle", "Bell pepper", "Belt", "Bench",
    "Bicycle", "Bicycle helmet", "Bicycle wheel", "Bidet", "Billboard",
    "Billiard table", "Binoculars", "Bird", "Blender", "Blue jay", "Boat",
    "Bomb", "Book", "Bookcase", "Boot", "Bottle", "Bottle opener",
    "Bow and arrow", "Bowl", "Bowling equipment", "Box", "Boy",
    "Brassiere", "Bread", "Briefcase", "Broccoli", "Bronze sculpture",
    "Brown bear", "Building", "Bull", "Burrito", "Bus", "Bust",
    "Butterfly", "Cabbage", "Cabinetry", "Cake", "Cake stand",
    "Calculator", "Camel", "Camera", "Can opener", "Canary", "Candle",
    "Candy", "Cannon", "Canoe", "Cantaloupe", "Car", "Carnivore",
    "Carrot", "Cart", "Cassette deck", "Castle", "Cat", "Cat furniture",
    "Caterpillar", "Cattle", "Ceiling fan", "Cello", "Centipede",
    "Chainsaw", "Chair", "Cheese", "Cheetah", "Chest of drawers",
    "Chicken", "Chime", "Chisel", "Chopsticks", "Christmas tree", "Clock",
    "Closet", "Clothing", "Coat", "Cocktail", "Cocktail shaker",
    "Coconut", "Coffee", "Coffee cup", "Coffee table", "Coffeemaker",
    "Coin", "Common fig", "Common sunflower", "Computer keyboard",
    "Computer monitor", "Computer mouse", "Container",
    "Convenience store", "Cookie", "Cooking spray", "Corded phone",
    "Cosmetics", "Couch", "Countertop", "Cowboy hat", "Crab", "Cream",
    "Cricket ball", "Crocodile", "Croissant", "Crown", "Crutch",
    "Cucumber", "Cupboard", "Curtain", "Cutting board", "Dagger",
    "Dairy Product", "Deer", "Desk", "Dessert", "Diaper", "Dice",
    "Digital clock", "Dinosaur", "Dishwasher", "Dog", "Dog bed", "Doll",
    "Dolphin", "Door", "Door handle", "Donut", "Dragonfly", "Drawer",
    "Dress", "Drill (Tool)", "Drink", "Drinking straw", "Drum", "Duck",
    "Dumbbell", "Eagle", "Earrings", "Egg (Food)", "Elephant",
    "Envelope", "Eraser", "Face powder", "Facial tissue holder", "Falcon",
    "Fashion accessory", "Fast food", "Fax", "Fedora", "Filing cabinet",
    "Fire hydrant", "Fireplace", "Fish", "Flag", "Flashlight", "Flower",
    "Flowerpot", "Flute", "Flying disc", "Food", "Food processor",
    "Football", "Football helmet", "Footwear", "Fork", "Fountain", "Fox",
    "French fries", "French horn", "Frog", "Fruit", "Frying pan",
    "Furniture", "Garden Asparagus", "Gas stove", "Giraffe", "Girl",
    "Glasses", "Glove", "Goat", "Goggles", "Goldfish", "Golf ball",
    "Golf cart", "Gondola", "Goose", "Grape", "Grapefruit", "Grinder",
    "Guacamole", "Guitar", "Hair dryer", "Hair spray", "Hamburger",
    "Hammer", "Hamster", "Hand dryer", "Handbag", "Handgun",
    "Harbor seal", "Harmonica", "Harp", "Harpsichord", "Hat",
    "Headphones", "Heater", "Hedgehog", "Helicopter", "Helmet",
    "High heels", "Hiking equipment", "Hippopotamus", "Home appliance",
    "Honeycomb", "Horizontal bar", "Horse", "Hot dog", "House",
    "Houseplant", "Human arm", "Human beard", "Human body", "Human ear",
    "Human eye", "Human face", "Human foot", "Human hair", "Human hand",
    "Human head", "Human leg", "Human mouth", "Human nose", "Humidifier",
    "Ice cream", "Indoor rower", "Infant bed", "Insect", "Invertebrate",
    "Ipod", "Isopod", "Jacket", "Jacuzzi", "Jaguar (Animal)", "Jeans",
    "Jellyfish", "Jet ski", "Jug", "Juice", "Kangaroo", "Kettle",
    "Kitchen & dining room table", "Kitchen appliance", "Kitchen knife",
    "Kitchen utensil", "Kitchenware", "Kite", "Knife", "Koala", "Ladder",
    "Ladle", "Ladybug", "Lamp", "Land vehicle", "Lantern", "Laptop",
    "Lavender (Plant)", "Lemon", "Leopard", "Light bulb", "Light switch",
    "Lighthouse", "Lily", "Limousine", "Lion", "Lipstick", "Lizard",
    "Lobster", "Loveseat", "Luggage and bags", "Lynx", "Magpie", "Mammal",
    "Man", "Mango", "Maple", "Maracas", "Marine invertebrates",
    "Marine mammal", "Measuring cup", "Mechanical fan", "Medical equipment",
    "Microphone", "Microwave oven", "Milk", "Miniskirt", "Mirror",
    "Missile", "Mixer", "Mixing bowl", "Mobile phone", "Monkey",
    "Moths and butterflies", "Motorcycle", "Mouse", "Muffin", "Mug",
    "Mule", "Mushroom", "Musical instrument", "Musical keyboard",
    "Nail (Construction)", "Necklace", "Nightstand", "Oboe",
    "Office building", "Office supplies", "Orange",
    "Organ (Musical Instrument)", "Ostrich", "Otter", "Oven", "Owl",
    "Oyster", "Paddle", "Palm tree", "Pancake", "Panda", "Paper cutter",
    "Paper towel", "Parachute", "Parking meter", "Parrot", "Pasta",
    "Pastry", "Peach", "Pear", "Pen", "Pencil case", "Pencil sharpener",
    "Penguin", "Perfume", "Person", "Personal care",
    "Personal flotation device", "Piano", "Picnic basket", "Picture frame",
    "Pig", "Pillow", "Pineapple", "Pitcher (Container)", "Pizza",
    "Pizza cutter", "Plant", "Plastic bag", "Plate", "Platter",
    "Plumbing fixture", "Polar bear", "Pomegranate", "Popcorn", "Porch",
    "Porcupine", "Poster", "Potato", "Power plugs and sockets",
    "Pressure cooker", "Pretzel", "Printer", "Pumpkin", "Punching bag",
    "Rabbit", "Raccoon", "Racket", "Radish", "Ratchet (Device)", "Raven",
    "Rays and skates", "Red panda", "Refrigerator", "Remote control",
    "Reptile", "Rhinoceros", "Rifle", "Ring binder", "Rocket",
    "Roller skates", "Rose", "Rugby ball", "Ruler", "Salad",
    "Salt and pepper shakers", "Sandal", "Sandwich", "Saucer", "Saxophone",
    "Scale", "Scarf", "Scissors", "Scoreboard", "Scorpion", "Screwdriver",
    "Sculpture", "Sea lion", "Sea turtle", "Seafood", "Seahorse",
    "Seat belt", "Segway", "Serving tray", "Sewing machine", "Shark",
    "Sheep", "Shelf", "Shellfish", "Shirt", "Shorts", "Shotgun", "Shower",
    "Shrimp", "Sink", "Skateboard", "Ski", "Skirt", "Skull", "Skunk",
    "Skyscraper", "Slow cooker", "Snack", "Snail", "Snake", "Snowboard",
    "Snowman", "Snowmobile", "Snowplow", "Soap dispenser", "Sock",
    "Sofa bed", "Sombrero", "Sparrow", "Spatula", "Spice rack", "Spider",
    "Spoon", "Sports equipment", "Sports uniform", "Squash (Plant)",
    "Squid", "Squirrel", "Stairs", "Stapler", "Starfish",
    "Stationary bicycle", "Stethoscope", "Stool", "Stop sign",
    "Strawberry", "Street light", "Stretcher", "Studio couch", "Submarine",
    "Submarine sandwich", "Suit", "Suitcase", "Sun hat", "Sunglasses",
    "Surfboard", "Sushi", "Swan", "Swim cap", "Swimming pool", "Swimwear",
    "Sword", "Syringe", "Table", "Table tennis racket", "Tablet computer",
    "Tableware", "Taco", "Tank", "Tap", "Tart", "Taxi", "Tea", "Teapot",
    "Teddy bear", "Telephone", "Television", "Tennis ball",
    "Tennis racket", "Tent", "Tiara", "Tick", "Tie", "Tiger", "Tin can",
    "Tire", "Toaster", "Toilet", "Toilet paper", "Tomato", "Tool",
    "Toothbrush", "Torch", "Tortoise", "Towel", "Tower", "Toy",
    "Traffic light", "Traffic sign", "Train", "Training bench", "Treadmill",
    "Tree", "Tree house", "Tripod", "Trombone", "Trousers", "Truck",
    "Trumpet", "Turkey", "Turtle", "Umbrella", "Unicycle", "Van", "Vase",
    "Vegetable", "Vehicle", "Vehicle registration plate", "Violin",
    "Volleyball (Ball)", "Waffle", "Waffle iron", "Wall clock", "Wardrobe",
    "Washing machine", "Waste container", "Watch", "Watercraft",
    "Watermelon", "Weapon", "Whale", "Wheel", "Wheelchair", "Whisk",
    "Whiteboard", "Willow", "Window", "Window blind", "Wine", "Wine glass",
    "Wine rack", "Winter melon", "Wok", "Woman", "Wood-burning stove",
    "Woodpecker", "Worm", "Wrench", "Zebra", "Zucchini"
};