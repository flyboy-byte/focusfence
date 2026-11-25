#!/usr/bin/env python3
"""Rebuild app/src/main/assets/classifier/nudenet_320n_f16.tflite (D-038).

    uv venv -p 3.12 v && VIRTUAL_ENV=v uv pip install "tensorflow-cpu==2.19.*" onnx onnxslim onnx2tf \
        onnx_graphsurgeon sng4onnx psutil onnxruntime tf_keras nudenet
    v/bin/python tools/convert_nudenet_model.py v/lib/python3.12/site-packages/nudenet/320n.onnx out/

Fixes the dynamic input to 1x3x320x320 (onnx2tf can't convert it otherwise), then onnx2tf writes
out/fixed_float16.tflite. onnx2tf downloads a pickled .npy to check outputs; a random one is written
first so nothing is downloaded or unpickled. Result: ops all v1 plus DEQUANTIZE v3, like the
MobileNet model, so the app's TFLite 2.14 runtime loads it. Checked against the ONNX original on
real screenshots: max per-class score difference 0.0008.
"""
import os, subprocess, sys
import numpy as np, onnx, onnxslim
from onnx import shape_inference

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
m = onnx.load(src)
d = m.graph.input[0].type.tensor_type.shape.dim
d[0].dim_value, d[2].dim_value, d[3].dim_value = 1, 320, 320
m = shape_inference.infer_shapes(onnxslim.slim(m))
fixed = os.path.join(out, "fixed.onnx")
onnx.save(m, fixed)
np.save(os.path.join(out, "calibration_image_sample_data_20x128x128x3_float32.npy"),
        np.random.rand(20, 128, 128, 3).astype("float32"), allow_pickle=False)
subprocess.run(["onnx2tf", "-i", os.path.abspath(fixed), "-o", "tf_out", "-n"], cwd=out, check=True)
print(os.path.join(out, "tf_out", "fixed_float16.tflite"))
