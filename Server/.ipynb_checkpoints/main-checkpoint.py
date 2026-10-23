import asyncio
import json
import traceback
import websockets
import cv2
import os
import torch
import torch.nn.functional as F
import timm
import ffmpeg
import time
import shutil
import numpy as np
import supervision as sv
from ultralytics import YOLO
from datetime import datetime
from paddleocr import PaddleOCR
from insightface.model_zoo import model_zoo
from insightface.utils import face_align

clients = {}
with open("../assets/emb.json", "r") as f:
    emb_data = json.loads(f.read())
with open("../assets/vech.json", "r") as f:
    vech_data = json.loads(f.read())
start = time.time()
fps_counter = 0

def _save_image(filename, image):
    cv2.imwrite(filename, image)

def save_file(filename, content):
    with open(filename, "wb") as f:
        f.write(content)

# FIX 1: Ensure both embedding and face directories exist. 
# Added os.makedirs with exist_ok=True to prevent crashes.
if os.path.exists("../assets/emb"):
    shutil.rmtree("../assets/emb")
os.makedirs("../assets/emb", exist_ok=True)

if os.path.exists("../assets/faces"):
    shutil.rmtree("../assets/faces")
os.makedirs("../assets/faces", exist_ok=True)

if os.path.exists("../assets/vechiles"):
    shutil.rmtree("../assets/vechiles")
os.makedirs("../assets/vechiles", exist_ok=True)

face_detector = model_zoo.get_model("../models/scrfd_10g_bnkps.onnx")
face_detector.prepare(ctx_id=-1, input_size=(640, 640))
emb_model = timm.create_model("hf_hub:gaunernst/vit_tiny_patch8_112.arcface_ms1mv3", pretrained=True).eval()
vech_dt = YOLO("../models/yolo26n.pt", verbose=False)
plate_dt = YOLO("../models/anpr_n.onnx", verbose=False)
ocr = PaddleOCR(device="npu:0")

def detect_faces(stream, offset, frame):
    bboxes, kpss = face_detector.detect(frame, max_num=0, metric="default")
    if bboxes is not None and len(bboxes) > 0:
        detections = sv.Detections(
            xyxy=bboxes[:, :4].astype(np.float32),
            confidence=bboxes[:, 4].astype(np.float32),
            class_id=np.zeros(len(bboxes), dtype=np.int32),
            data={"kps": np.asarray(kpss, dtype=np.float32)}
        )
        tracked = stream.face_tracker.update_with_detections(detections)
        if tracked.tracker_id is not None and len(tracked) > 0:
            original_boxes = detections.xyxy
            tracked_kpss = tracked.data.get("kps")
            for t_id, xyxy, conf, kps in zip(tracked.tracker_id, tracked.xyxy, tracked.confidence, tracked_kpss):
                if t_id not in stream.face_id and conf >= 0.8:
                    stream.face_id.add(t_id)
                    x1, y1, x2, y2 = map(int, xyxy)
                    try:
                        #face = frame[y1:y2, x1:x2]
                        #cv2.imwrite(f"../assets/faces/{stream.face_count}_{conf}.jpg", face)
                        aligned_face = face_align.norm_crop(frame, landmark=kps)
                        aligned_face = np.transpose(aligned_face, (2, 0, 1))
                        aligned_face = np.expand_dims(aligned_face, axis=0)
                        aligned_face = torch.from_numpy(aligned_face).float()
                        aligned_face = (aligned_face - 127.5) / 127.5
                        embs = emb_model(aligned_face)
                        embs = F.normalize(embs, dim=1)
                        np.save(f"../assets/emb/{stream.name}_{stream.face_count}", embs.detach().cpu().numpy()[0])
                        now = datetime.now()
                        dt = now.strftime("%Y-%m-%d")
                        if dt not in emb_data:
                            emb_data[dt] = {}
                        if stream.name not in emb_data[dt]:
                            emb_data[dt][stream.name] = {"st": time.time(), "dt":[], "src": stream.source}
                        vd = {"tm": time.time(), "vt": offset, "box": [x1, y1, x2, y2], "n": f"{stream.name}_{stream.face_count}"}
                        emb_data[dt][stream.name]["dt"].append(vd)  
                        with open("../assets/emb.json", "wb") as f:
                            f.write(json.dumps(emb_data).encode("utf-8"))
                        print("embedding saved")
                        stream.face_count += 1
                    except Exception as e:
                        print(e)

def detect_vechiles(stream, offset, image):
    global vech_data
    results = vech_dt.predict(image, verbose=False)
    detections = sv.Detections.from_ultralytics(results[0])
    tracked = stream.vech_tracker.update_with_detections(detections)
    if tracked.tracker_id is not None:
        for t_id, xyxy, c_id, conf in zip( tracked.tracker_id, tracked.xyxy, tracked.class_id, tracked.confidence):
            if t_id not in stream.vech_id:
                stream.vech_id.add(t_id)
                x1, y1, x2, y2 = map(int, xyxy)
                if conf >= 0.5 and int(c_id) in [1, 2, 3, 5, 6, 7]:
                    # store vechile info
                    # perform ANPR
                    now = datetime.now()
                    dt = now.strftime("%Y-%m-%d")
                    if dt not in vech_data:
                        vech_data[dt] = {}
                    if stream.name not in vech_data[dt]:
                        vech_data[dt][stream.name] = {"st": time.time(), "dt":[], "src": stream.source}
                    vd = {"cs": int(c_id), "pt":"", "tm": time.time(), "vt": offset, "box": [x1, y1, x2, y2]}
                    vech_cropped = image[y1:y2, x1:x2]
                    stream.vech_count += 1
                    #cv2.imwrite(f"../assets/vechiles/{stream.vech_count}.jpg", vech_cropped)
                    results = plate_dt.predict(vech_cropped, verbose=False)
                    p_boxes = results[0].boxes
                    if len(p_boxes) > 0:
                        px1, py1, px2, py2 = map(int, p_boxes[0].xyxy[0])
                        gray = cv2.cvtColor(vech_cropped[py1:py2, px1:px2], cv2.COLOR_BGR2GRAY)
                        score = cv2.Laplacian(gray, cv2.CV_64F).var()
                        if score > 20:
                            print("reading plate: ", end="")
                            results = ocr.predict(vech_cropped[py1:py2, px1:px2])[0]
                            score = max(results["rec_scores"])
                            if score > 0.5:
                                vd["pt"] = results["rec_texts"][results["rec_scores"].index(score)]
                                print(results["rec_texts"][results["rec_scores"].index(score)])
                            else:
                                print("N/A")
                            # cv2.imwrite(
                            #     f"../assets/vechiles/{t_id}.jpg",
                            #     vech_cropped[py1:py2, px1:px2]
                            # )
                    vech_data[dt][stream.name]["dt"].append(vd)
                    with open("../assets/vech.json", "wb") as f:
                            f.write(json.dumps(vech_data).encode("utf-8"))
                    print("vechile written")

class Stream:
    def __init__(self, url, mode="rtsp"):
        self.name = "NA"
        self.source = ""
        self.loc = "NA"
        self.face_tracker = sv.ByteTrack()
        self.vech_tracker = sv.ByteTrack()
        self.is_face_detecting = False
        self.is_vech_detecting = False
        self.is_running = False
        self.mode = mode
        self.url = url
        self.face_id = set()
        self.vech_id = set()
        self.vech_count = 0
        self.face_count = 0
        self.streams = []
        self.fps_start = time.time()
        self.fps_counter = 0
        self.task = None
    def update(self, data: dict):
        for key, value in data.items():
            setattr(self, key, value)
    def count_fps(self):
        self.fps_counter += 1

        now = time.time()
        elapsed = now - self.fps_start

        if elapsed >= 1.0:
            fps = self.fps_counter / elapsed
            print(f"[{self.name}] FPS: {fps:.2f}")

            self.fps_counter = 0
            self.fps_start = now
    async def detect_faces(self, offset, frame):
        try:
            await asyncio.to_thread(detect_faces, self, offset, frame)
        finally:
            self.is_face_detecting = False
    async def detect_vechiles(self, offset, frame):
        try:
            await asyncio.to_thread(detect_vechiles, self, offset, frame)
        finally:
            self.is_vech_detecting = False
    async def send_user(self, ws):
        obj = {"n": self.name, "nv": 0, "np": 0}
        while True:
            obj["nv"] = self.vech_count
            obj["nf"] = self.face_count
            await ws.send(json.dumps(obj))
            await asyncio.sleep(3)

async def handler(websocket):
    print("someone joined the server.")

    if websocket not in clients:
        clients[websocket] = []
    try:
        async for message in websocket:
            obj = json.loads(message)
            for i in obj:
                chk = False
                for n, j in enumerate(clients[websocket]):
                    if j.name == i["name"] and j.url == i["url"]:
                        clients[websocket][n].update(i)
                        chk = True
                        break
                if chk:
                    continue
                s = Stream(i["url"])
                s.update(i)
                clients[websocket].append(s)

    except websockets.exceptions.ConnectionClosed:
        print("Client disconnected abruptly.")
    except Exception as e:
        print(f"Handler error: {e}")
    finally:
        if websocket in clients:
            for s in clients[websocket]:
                s.is_running = False
                if s.task:
                    s.task.cancel()
            del clients[websocket]
            print("Removed client from active clients dictionary.")


async def oper_stream(ws, stream):
    global start, fps_counter
    print("Starting stream: ", stream.name)
    try:
        cap = cv2.VideoCapture(stream.source)
        stream.task = asyncio.create_task(
            stream.send_user(ws)
        )
        while cap.isOpened() and stream.is_running:
            if stream.detection:
                ret, frame = await asyncio.to_thread(cap.read)
                if not ret:
                    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    continue
                stream.count_fps()
                if not stream.is_face_detecting:
                    stream.is_face_detecting = True
                    asyncio.create_task(stream.detect_faces(cap.get(cv2.CAP_PROP_POS_MSEC), frame))
                if not stream.is_vech_detecting:
                    #print("detecting")
                    stream.is_vech_detecting = True
                    asyncio.create_task(stream.detect_vechiles(cap.get(cv2.CAP_PROP_POS_MSEC), frame))
            await asyncio.sleep(0.001)

            
    # FIX 4: Catch CancelledError to cleanly handle disconnections
    except asyncio.CancelledError:
        print(f"Stream task for {stream.name} was cancelled by client disconnect.")
    except Exception:
        print(traceback.format_exc())
    finally:
        stream.detection = False

async def process_stream():
    while True:
        # FIX 6: Use list(clients.items()) to prevent 'dictionary changed size during iteration' crash
        for ws, client in list(clients.items()):
            for stream in client:
                # if not stream.stream and not stream.detection:
                #     continue
                if not stream.is_running:
                    stream.is_running = True
                    stream.task = asyncio.create_task(oper_stream(ws, stream))
        await asyncio.sleep(1)


async def main(url="0.0.0.0", port=8080):
    async with websockets.serve(handler, url, port):
        print("WebSocket server running on ws://0.0.0.0:8080")
        await process_stream()
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())