"""Fine-tune YOLO11m and export a fixed-shape, pre-NMS ONNX detector."""
import argparse
import os
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "false")

from ultralytics import YOLO


def main():
    p = argparse.ArgumentParser()
    p.add_argument("checkpoint", type=Path)
    p.add_argument("dataset", type=Path)
    p.add_argument("runs", type=Path)
    p.add_argument("--epochs", type=int, default=25)
    p.add_argument("--batch", type=int, default=12)
    p.add_argument("--device", default="0")
    p.add_argument("--name", default="care11m")
    p.add_argument("--save-period", type=int, default=-1)
    a = p.parse_args()
    model = YOLO(str(a.checkpoint.resolve()))
    model.train(data=str(a.dataset.resolve()), project=str(a.runs.resolve()), name=a.name,
                imgsz=320, epochs=a.epochs, batch=a.batch, device=a.device, workers=0,
                seed=3414, deterministic=False, amp=False, plots=False, patience=10,
                save_period=a.save_period,
                fliplr=0, flipud=0, translate=.08, scale=.27, mosaic=.45, close_mosaic=5)
    print("BEST", model.trainer.best)
    print(YOLO(str(model.trainer.best)).export(format="onnx", imgsz=320, opset=17,
          dynamic=False, simplify=False, nms=False, device="cpu"))


if __name__ == "__main__":
    main()
