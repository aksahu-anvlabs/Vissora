import numpy as np
import cv2

def letter_box(img, target_size=(640, 640), color=(114, 114, 114)):
    h, w = img.shape[:2]
    scale = min(target_size[0] / h, target_size[1] / w)
    new_w, new_h = int(round(w * scale)), int(round(h * scale))

    # Resize maintaining aspect ratio
    resized = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_LINEAR)

    # Calculate padding to reach 640x640
    pad_w = target_size[1] - new_w
    pad_h = target_size[0] - new_h
    top, bottom = pad_h // 2, pad_h - (pad_h // 2)
    left, right = pad_w // 2, pad_w - (pad_w // 2)

    # Pad with neutral gray (114, 114, 114) like Ultralytics
    padded = cv2.copyMakeBorder(
      resized, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color
    )
    return scale, top, bottom, left, right, padded

def get_org_coord(box_x1, box_y1, box_w, box_h, pad_left, pad_top, scale_factor, orig_w, orig_h):
    # 1. Remove letterbox padding
    x_unpadded = box_x1 - pad_left
    y_unpadded = box_y1 - pad_top
    
    # 2. Scale back using float division
    x1 = int(x_unpadded / scale_factor)
    y1 = int(y_unpadded / scale_factor)
    w_box = int(box_w / scale_factor)
    h_box = int(box_h / scale_factor)
    
    # 3. Compute x2, y2 and clamp strictly to the provided image bounds
    x2 = min(orig_w, max(0, x1 + w_box))
    y2 = min(orig_h, max(0, y1 + h_box))
    x1 = max(0, min(orig_w, x1))
    y1 = max(0, min(orig_h, y1))
    
    return y1, y2, x1, x2

def closest_color(hex_color: str) -> str:
    hex_color = hex_color.lstrip("#")

    if len(hex_color) != 6:
        raise ValueError("Expected a 6-digit hex color, e.g. #FF0000")

    r, g, b = (int(hex_color[i:i + 2], 16) for i in (0, 2, 4))

    colors = {
        "red":   (255, 0, 0),
        "green": (0, 255, 0),
        "blue":  (0, 0, 255),
        "white": (255, 255, 255),
        "black": (0, 0, 0),
    }

    return min(colors, key=lambda name: sum((a - c) ** 2 for a, c in zip((r, g, b), colors[name])))

def filter_color(frame, hex):
    hsv_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    color_ranges = {
        'red': [(np.array([0, 70, 50]), np.array([10, 255, 255])), (np.array([170, 70, 50]), np.array([180, 255, 255]))],
        'blue': [(np.array([100, 70, 50]), np.array([130, 255, 255]))],
        'green': [(np.array([40, 70, 50]), np.array([90, 255, 255]))],
        'white': [(np.array([0, 0, 200]), np.array([180, 40, 255]))],
        'black': [(np.array([0, 0, 0]), np.array([180, 255, 50]))]
    }
    
    color_pixel_counts = {}
    
    for color, ranges in color_ranges.items():
        mask = np.zeros(hsv_frame.shape[:2], dtype=np.uint8)
        for lower, upper in ranges:
            current_mask = cv2.inRange(hsv_frame, lower, upper)
            mask = cv2.bitwise_or(mask, current_mask)
        
        color_pixel_counts[color] = cv2.countNonZero(mask)
        
    dominant_color = max(color_pixel_counts, key=color_pixel_counts.get)
    return dominant_color == closest_color(hex)