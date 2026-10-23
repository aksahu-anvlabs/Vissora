import asyncio
import cv2
import json
from helper import get_org_coord, letter_box
from insightface.model_zoo import model_zoo
from insightface.utils import face_align
import numpy as np
from paddleocr import PaddleOCR
import supervision as sv
import sys
import time
import timm
import torch
from datetime import datetime
import torch.nn.functional as F
from ultralytics import YOLO

sys.path.append("assets/ocr")
start_time = time.time()
emb_data = {}
byte_tracker = sv.ByteTrack()
face_byte_tracker = sv.ByteTrack()
face_id = set()
dt_vechile = set()
# vech_dt = YOLO("../models/yolo26n.pt", verbose=False)
# plate_dt = YOLO("../models/anpr_n.onnx", verbose=False)
emb_model = timm.create_model(
    "hf_hub:gaunernst/vit_tiny_patch8_112.arcface_ms1mv3", pretrained=True
).eval()
# ocr = PaddleOCR(device="npu:0")
face_dt = cv2.FaceDetectorYN.create(
    model="../models/face_detection_yunet_2026may.onnx",
    config="",
    score_threshold=0.7,
    input_size=(320, 320),
    nms_threshold=0.3,
    top_k=5000,
)

arcface_template = np.array(
    [
        [38.2946, 51.6963],  # left eye
        [73.5318, 51.5014],  # right eye
        [56.0252, 71.7366],  # nose
        [41.5493, 92.3655],  # left mouth
        [70.7299, 92.2041],  # right mouth
    ],
    dtype=np.float32,
)

face_detector = model_zoo.get_model("../models/scrfd_10g_bnkps.onnx")
face_detector.prepare(ctx_id=-1, input_size=(640, 640))


def detect_vechile(image, stream=None, mark=False):
    st = time.time()
    results = vech_dt.predict(image, verbose=False)
    detections = sv.Detections.from_ultralytics(results[0])
    tracked = byte_tracker.update_with_detections(detections)
    if tracked.tracker_id is not None:
        for t_id, xyxy, c_id, conf in zip(
            tracked.tracker_id,
            tracked.xyxy,
            tracked.class_id,
            tracked.confidence,
        ):
            if t_id not in dt_vechile:
                dt_vechile.add(t_id)
                x1, y1, x2, y2 = map(int, xyxy)
                if conf >= 0.5:
                    # store vechile info
                    # perform ANPR
                    vech_cropped = image[y1:y2, x1:x2]
                    p_boxes = detect_plate(vech_cropped)
                    if len(p_boxes) > 0:
                        px1, py1, px2, py2 = map(int, p_boxes[0].xyxy[0])
                        score = get_sharpness_score(vech_cropped[py1:py2, px1:px2])
                        if score > 20:
                            read_plate(vech_cropped[py1:py2, px1:px2])
                            cv2.imwrite(
                                f"../assets/vechiles/{t_id}.jpg",
                                vech_cropped[py1:py2, px1:px2],
                            )
                    # cv2.imwrite(f"../assets/vechiles/{t_id}.jpg", image[y1:y2, x1:x2])
    # print("vechile time:", time.time() - st)


def detect_plate(image):
    st = time.time()
    results = plate_dt.predict(image, verbose=False)
    # print("plate time:", time.time() - st)
    return results[0].boxes


def detect_faces(img, w=640, h=640, stream=None):
    global start_time
    bboxes, kpss = face_detector.detect(img, max_num=0, metric="default")
    print(time.time() - start_time)
    start_time = time.time()
    # 5. Visualize the results
    if bboxes is not None and kpss is not None:
        detections = sv.Detections(
            xyxy=bboxes[:, :4].astype(np.float32),
            confidence=bboxes[:, 4].astype(np.float32),
            class_id=np.zeros(len(bboxes), dtype=np.int32),
            data={"kps": np.asarray(kpss, dtype=np.float32)}
        )
        tracked = face_byte_tracker.update_with_detections(detections)

        if tracked.tracker_id is not None and len(tracked) > 0:
            original_boxes = detections.xyxy

            for t_id, xyxy, conf in zip(
                tracked.tracker_id,
                tracked.xyxy,
                tracked.confidence,
            ):

                # Match the tracked box to its original detection
                x1t, y1t, x2t, y2t = xyxy

                xx1 = np.maximum(original_boxes[:, 0], x1t)
                yy1 = np.maximum(original_boxes[:, 1], y1t)
                xx2 = np.minimum(original_boxes[:, 2], x2t)
                yy2 = np.minimum(original_boxes[:, 3], y2t)

                inter = (
                    np.maximum(0, xx2 - xx1)
                    * np.maximum(0, yy2 - yy1)
                )

                area_t = max(0, x2t - x1t) * max(0, y2t - y1t)
                areas = (
                    np.maximum(0, original_boxes[:, 2] - original_boxes[:, 0])
                    * np.maximum(0, original_boxes[:, 3] - original_boxes[:, 1])
                )

                iou = inter / np.maximum(area_t + areas - inter, 1e-6)
                best_idx = int(np.argmax(iou))

                if iou[best_idx] < 0.5:
                    continue

                kps = kpss[best_idx]

                if t_id in face_id:
                    continue

                face_id.add(t_id)

                # Continue with your existing embedding code
                x1, y1, x2, y2 = map(int, xyxy)
                aligned_face = face_align.norm_crop(img, landmark=kps)
                aligned_face = np.transpose(aligned_face, (2, 0, 1))
                aligned_face = np.expand_dims(aligned_face, axis=0)
                aligned_face = torch.from_numpy(aligned_face).float()
                aligned_face = (aligned_face - 127.5) / 127.5
                embs = emb_model(aligned_face)
                embs = F.normalize(embs, dim=1)
                if stream:
                    np.save(f"../assets/emb/{stream.name}_{stream.face_count}", embs.detach().cpu().numpy()[0])
                    face_img = aligned_face[0].detach().cpu().numpy()
                    face_img = np.transpose(face_img, (1, 2, 0))
                    face_img = ((face_img + 1.0) * 127.5).clip(0, 255).astype(np.uint8)

                    cv2.imwrite(
                        f"../assets/vechiles/{stream.name}_{stream.face_count}.jpg",
                        face_img
                    )
                    now = datetime.now()
                    dt = now.strftime("%Y-%m-%d")
                    if dt not in emb_data:
                        emb_data[dt] = []
                        
                    # Format the name safely for JSON as well
                    emb_data[dt].append([time.time(), f"{stream.name}_{stream.face_count}", x1, y1, x2, y2])
                    with open("../assets/emb.json", "wb") as f:
                        f.write(json.dumps(emb_data).encode("utf-8"))
                    print("embedding saved")
                    stream.face_count += 1


def predict_number(image):
    result = text_recognizer.predict(image)
    predicted_text = result.get("prediction", "")
    confidence = result.get("confidence", 0.0)
    return predicted_text, confidence


def get_sharpness_score(image):
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    return cv2.Laplacian(gray, cv2.CV_64F).var()


def get_emb(image, face):
    landmarks = face[4:14].reshape(5, 2).astype(np.float32)
    transform, _ = cv2.estimateAffinePartial2D(
        landmarks, arcface_template, method=cv2.LMEDS
    )
    aligned_face = cv2.warpAffine(
        image, transform, (112, 112), flags=cv2.INTER_LINEAR
    )
    aligned_face = np.transpose(aligned_face, (2, 0, 1))
    aligned_face = np.expand_dims(aligned_face, axis=0)
    aligned_face = torch.from_numpy(aligned_face).float()

    aligned_face = (aligned_face - 127.5) / 127.5

    embs = emb_model(aligned_face)
    embs = F.normalize(embs, dim=1)
    return embs.detach().cpu().numpy()[0]


def read_plate(frame):
    print("reading plate: ", end="")
    results = ocr.predict(frame)[0]
    score = max(results["rec_scores"])
    if score > 0.5:
        print(results["rec_texts"][results["rec_scores"].index(score)])
    else:
        print("N/A")


# tester

# begin = time.perf_counter()
# img = cv2.imread("vechiles/0.jpg")
# res = text_recognizer.predict(plate_crop)
# detect_vechile(img)
# detect_plate(img)

# print("time lapsed:", time.perf_counter() - begin)