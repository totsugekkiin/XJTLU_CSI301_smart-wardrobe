"""Measure precision and recall at the Android score and NMS thresholds."""
import argparse
from pathlib import Path
import time

import numpy as np
import onnxruntime as ort
from PIL import Image


def iou(a, b):
    wh = np.maximum(0, np.minimum(a[2:], b[2:]) - np.maximum(a[:2], b[:2]))
    inter = wh.prod()
    return inter / max(1e-9, (a[2:] - a[:2]).prod() + (b[2:] - b[:2]).prod() - inter)


def detect(session, filename, score=.45):
    image = Image.open(filename).convert("RGB")
    scale = 320 / max(image.size)
    w, h = (max(1, round(side * scale)) for side in image.size)
    x, y = (320 - w) // 2, (320 - h) // 2
    canvas = Image.new("RGB", (320, 320), (114,) * 3)
    canvas.paste(image.resize((w, h), Image.Resampling.BILINEAR), (x, y))
    value = (np.array(canvas, dtype=np.float32) / 255).transpose(2, 0, 1)[None]
    out = session.run(None, {"images": value})[0][0].T
    ids, scores = out[:, 4:].argmax(axis=1), out[:, 4:].max(axis=1)
    candidates = np.flatnonzero(scores >= score)
    hits = []
    for i in candidates[np.argsort(-scores[candidates])]:
        xy, wh = out[i, :2], out[i, 2:4] * .5
        box = np.clip((np.r_[xy-wh, xy+wh] - [x,y,x,y]) / [w,h,w,h], 0, 1)
        if any(iou(box, hit[1]) > .48 for hit in hits):
            continue
        if np.any(box[2:] <= box[:2]):
            continue
        hits.append((ids[i], box, scores[i]))
        if len(hits) == 12:
            break
    return hits


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--photos", nargs="*", type=Path, default=[])
    args = parser.parse_args()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(args.model, sess_options=options, providers=["CPUExecutionProvider"])
    names = [line.split("\t")[0] for line in Path(__file__).with_name("classes.tsv").read_text(encoding="utf-8").splitlines()]
    scores = []
    tp = fp = missed = 0
    for file in sorted((args.dataset / "images" / "test").glob("*.jpg")):
        expected = []
        for line in (args.dataset / "labels" / "test" / (file.stem + ".txt")).read_text().splitlines():
            label, x, y, w, h = map(float, line.split())
            expected.append((label, np.array([x-w/2, y-h/2, x+w/2, y+h/2])))
        now = time.perf_counter()
        for label, box, confidence in detect(session, file):
            matched = next((i for i, (target, rect) in enumerate(expected) if label == target and iou(box, rect) >= .5), None)
            if matched is None:
                fp += 1
            else:
                tp += 1
                expected.pop(matched)
        scores.append((time.perf_counter() - now) * 1000)
        missed += len(expected)
    print(f"synthetic samples={len(scores)}, TP={tp}, FP={fp}, FN={missed}, "
          f"precision={tp/max(1,tp+fp):.3f}, recall={tp/max(1,tp+missed):.3f}, "
          f"Windows CPU ms P50/P95={np.percentile(scores,[50,95]).round(1)}")
    for image in args.photos:
        print(image.name, [(names[index], round(float(confidence), 3)) for index, _, confidence in detect(session, image)])


if __name__ == "__main__":
    main()
