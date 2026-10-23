from flask import Flask, request, jsonify, send_from_directory
from pathlib import Path
import torch.nn.functional as F
from datetime import datetime
import numpy as np
import traceback
import helper
import torch
import timm
import json
import cv2
import os

app = Flask(__name__)
face_dt = cv2.FaceDetectorYN.create(
    model="../models/face_detection_yunet_2023mar.onnx",
    config="",
    score_threshold=0.7,
    input_size=(320, 320),
    nms_threshold=0.3,
    top_k=5000
)
arcface_template = np.array([
    [38.2946, 51.6963],  # left eye
    [73.5318, 51.5014],  # right eye
    [56.0252, 71.7366],  # nose
    [41.5493, 92.3655],  # left mouth
    [70.7299, 92.2041]   # right mouth
], dtype=np.float32)
emb_model = timm.create_model("hf_hub:gaunernst/vit_tiny_patch8_112.arcface_ms1mv3", pretrained=True).eval()

def get_emb(img, face):
    landmarks = face[4:14].reshape(5, 2).astype(np.float32)
    transform,_ = cv2.estimateAffinePartial2D(landmarks, arcface_template, method=cv2.LMEDS)
    aligned_face = cv2.warpAffine(img, transform, (112, 112), flags=cv2.INTER_LINEAR)
    transform,_ = cv2.estimateAffinePartial2D(landmarks, arcface_template, method=cv2.LMEDS)
    aligned_face = cv2.warpAffine(img, transform, (112, 112), flags=cv2.INTER_LINEAR)
    aligned_face = np.transpose(aligned_face, (2, 0, 1))
    aligned_face = np.expand_dims(aligned_face, axis=0)
    aligned_face = torch.from_numpy(aligned_face).float()

    aligned_face = (aligned_face - 127.5) / 127.5

    emb = emb_model(aligned_face)
    emb = F.normalize(emb, dim=1)
    emb = emb.detach().cpu().numpy()[0]

    return emb

@app.route("/match", methods=["POST"])
def upload():
    file = request.files.get("file")
    request_body = request.form.get("requestBody")
    data = json.loads(request_body)

    sd = data.get("sd")
    ed = data.get("ed")
    st = data.get("st")
    et = data.get("et")

    if not file:
        return {"error": "No file provided"}, 400

    img_array = np.frombuffer(file.read(), dtype=np.uint8)
    img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
    h, w = img.shape[:2]
    face_dt.setInputSize((w, h))
    _, faces = face_dt.detect(img)
    if len(faces) > 0:
        person = get_emb(img, faces[0])
        
        try:
            sd = datetime.strptime(sd, "%d-%m-%y").date()
            ed = datetime.strptime(ed, "%d-%m-%y").date()

            st = datetime.strptime(st, "%H:%M:%S").time()
            et = datetime.strptime(et, "%H:%M:%S").time()

            start_timestamp = datetime.combine(sd, st).timestamp()
            end_timestamp = datetime.combine(ed, et).timestamp()
            vc = []
            with open("../assets/emb.json", "r") as f:
                data = json.loads(f.read())
                for i, j in data.items():
                    dt = datetime.strptime(i, "%Y-%m-%d").date()
                    if sd <= dt <= ed:
                        for m, n in j.items():
                            cap = cv2.VideoCapture(n["src"])
                            for det in n["dt"]:
                                det_dt = datetime.fromtimestamp(float(det["tm"]))
                                if start_timestamp <= float(det["tm"]) <= end_timestamp:
                                    cap.set(cv2.CAP_PROP_POS_MSEC, float(det["vt"]))
                                    success, frame = cap.read()
                                    if success:
                                        x1, y1, x2, y2 = det["box"]
                                        face = frame[y1:y2, x1:x2]
                                        h, w = face.shape[:2]
                                        face_dt.setInputSize((w, h))
                                        _, f = face_dt.detect(face)
                                        if len(f) > 0:
                                            emb = get_emb(face, f[0])
                                            similarity = np.sum(person * emb, axis=0)
                                            if similarity > 0.25:
                                                cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 0, 255), 4)
                                                cv2.putText(frame, str(similarity), (x1, y1), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2, cv2.LINE_AA)
                                                cv2.imwrite(f"../assets/.tmp/images/{len(vc)}.jpg", frame)
                                                vc.append([m, det["tm"]])
        except Exception as e:
            print(traceback.format_exc())

    return jsonify(vc), 200

FILES_DIR = Path("../assets/.tmp/images/").resolve()

@app.route("/files")
def list_files():
    return jsonify([
        {
            "name": p.name,
            "url": f"/files/{p.name}"
        }
        for p in FILES_DIR.iterdir()
        if p.is_file()
    ])

@app.route("/vech_info", methods=["POST"])
def get_vechinfo():
    with open("../assets/vech.json", "r") as f:
        return f.read(), 200

@app.route("/files/<path:filename>")
def serve_file(filename):
    return send_from_directory(FILES_DIR, filename)

@app.route("/get_vechile", methods=["POST"])
def get_vech():
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"message": "Invalid or missing JSON body"}), 400

    vcls = ["Any", "Bicycle", "Car", "Motorcycle", "Bus", "Truck"]

    sd = data.get("sd")
    ed = data.get("ed")
    st = data.get("st")
    et = data.get("et")
    pt = data.get("vrn")
    md = data.get("mode")
    clr = data.get("color")

    if md in vcls:
        md = vcls.index(md)
    else:
        md = -1

    try:
        sd = datetime.strptime(sd, "%d-%m-%y").date()
        ed = datetime.strptime(ed, "%d-%m-%y").date()

        st = datetime.strptime(st, "%H:%M:%S").time()
        et = datetime.strptime(et, "%H:%M:%S").time()

        start_timestamp = datetime.combine(sd, st).timestamp()
        end_timestamp = datetime.combine(ed, et).timestamp()
        vc = []
        with open("../assets/vech.json", "r") as f:
            data = json.loads(f.read())
            for i, j in data.items():
                dt = datetime.strptime(i, "%Y-%m-%d").date()
                if sd <= dt <= ed:
                    for m, n in j.items():
                        cap = cv2.VideoCapture(n["src"])
                        for det in n["dt"]:
                            if 0 < det["cs"] < 9:
                                if md != 0 and md != det["cs"]:
                                    continue
                                if pt not in det["pt"]:
                                    continue
                                det_dt = datetime.fromtimestamp(float(det["tm"]))
                                if start_timestamp <= float(det["tm"]) <= end_timestamp:
                                    cap.set(cv2.CAP_PROP_POS_MSEC, float(det["vt"]))
                                    success, frame = cap.read()
                                    if success:
                                        x1, y1, x2, y2 = det["box"]
                                        if clr == "" or helper.filter_color(frame[y1:y2, x1:x2], clr):
                                            cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 0, 255), 4)
                                            cv2.imwrite(f"../assets/.tmp/images/{len(vc)}.jpg", frame)
                                            vc.append([m, det["tm"]])

        return jsonify(vc), 200
    except Exception as e:
        print(traceback.format_exc())
        return "[]", 400

app.run(host="0.0.0.0", port=5000)