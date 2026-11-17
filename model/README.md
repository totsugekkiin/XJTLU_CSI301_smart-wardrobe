# 洗标图形检测

## 当前应用部署模型

应用使用用户提供的 `care-yolo.pt`（SHA-256 `ca900d2da40ae8843d5a92c62b7b08bd486680596dbA24c9d7c821053159b646`），由 Ultralytics 导出固定 640 × 640、opset 17、无内置 NMS 的 FP32 ONNX。ONNX SHA-256 为 `b36a0ccf9b19991c6587f6c53dc95b2ddab997d86d00216d4e0a2b06eee5696d`。输出形状为 `1 × 18 × 8400`，部署类别及顺序见 `app/src/main/assets/care-classes.tsv`。输入转灰度，再按比例缩小并居中用黑色补边。置信度阈值 0.60，按类 NMS 的 IoU 阈值 0.70。

0.6.0 沿用 14 类权重，App 提供其中 11 类自动候选。类别表将仅氧漂、中温熨烫和中温滚筒烘干标为 `manual`。每个位置先按完整模型输出决定得分最高的类别，若该类别标为手动，则跳过这一候选。三类仍可在对应类别中手动选择。

相机预览与推理解耦。预览和识别使用同一 CameraX viewport，推理裁取画面中心的取景框区域。每次推理结束即取最新可用帧，实际频率取决于设备的推理速度。当前帧的图标至少与最近两帧之一匹配，才显示实时检测框并允许进入核对。Mate 60 上的对焦距离、续航、延迟和实拍对照效果仍待测试。

## 0.3.0 自训练实验归档

APK 内的 `care-yolo11m.onnx` 是从 [Ultralytics 官方 YOLO11m 权重](https://github.com/ultralytics/assets/releases/tag/v8.3.0) 初始化后微调的 YOLO11m Detect。输入为 FP32 RGB NCHW `1 × 3 × 320 × 320`，输出为 `1 × 34 × 2100`。Android 用 CPU 运行 ONNX Runtime，候选阈值为 0.45，跨类 NMS 的 IoU 阈值为 0.48。模型和类别表均随 APK 打包。

训练图来自 [Kobo Labs 的 MIT SVG 模板](https://github.com/kobo-labs-open-source/care-label-symbols/tree/5eb40020ce03a0f4251e0963db1571d6e1654c8b)。合成图模拟了织物亮度变化、不同字形、角度、模糊与 JPEG 压缩。模板许可见 `templates/LICENSE`。两个干洗模板的文件名和图中文字不同，映射按图中实际印刷的字母设置。部分专业湿洗、阴干等图形仍需手动选择。

训练使用 Ultralytics 8.4.211、PyTorch 2.11.0+cu128 和 RTX 4060 Laptop。使用 Python 3.12 安装 `ultralytics`、`resvg-py`、`onnx` 和 `onnxruntime` 后，可按下列方式复跑。首阶段从 COCO 预训练权重开始，选用训练中期的最佳权重做第二阶段；不同硬件、字体或 CUDA 设置可能改变结果。

```sh
python model/generate.py /path/to/care-base
python model/train.py yolo11m.pt /path/to/care-base/data.yaml /path/to/runs --epochs 24 --batch 12 --save-period 1
python model/generate.py /path/to/care-varied --train 640 --val 120 --test 144 --seed-offset 9000 --varied
python model/train.py /path/to/runs/care11m/weights/epoch7.pt /path/to/care-varied/data.yaml /path/to/runs --epochs 12 --batch 16 --name care11m-varied
python model/check.py /path/to/care-yolo11m.onnx /path/to/care-varied
```

此模型使用合成数据微调，真实照片仅用于检查。实拍中的小图标、折皱、模糊及不同厂商的字形可能造成漏检或误判。结果在核对后才写入档案。模型许可见 [Ultralytics](https://www.ultralytics.com/license)。

交付 ONNX 为 80,341,601 字节，SHA-256 为 `de1772048564e9bdd964748e2e5265b04f3c80fc43f178752103d6f38cb04249`。

在 144 张独立生成的合成测试图上，按类别一致且 IoU ≥ 0.5 计为命中，共 455 个命中、3 个误检和 5 个漏检。Windows CPU 两线程的 P50/P95 为约 184/235 ms，包含图片解码、预处理和后处理。结果仅代表合成样本。少量[公开实拍](https://github.com/st-den/thesis-project)中出现了整图无结果、框选后部分检出，以及将禁止烘干误识别为可烘干的情况。Mate 60 的性能和实拍准确率尚未验证。
