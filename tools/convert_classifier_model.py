#!/usr/bin/env python3
"""Rebuild app/src/main/assets/classifier/nsfw_mobilenet_v2_f16.tflite.

    pip install tensorflow-cpu
    # download + unzip https://github.com/GantMan/nsfw_model/releases/download/1.1.0/nsfw_mobilenet_v2_140_224.zip
    python3 tools/convert_classifier_model.py mobilenet_v2_140_224 out.tflite

float16 weights, not int8 "dynamic range": dynamic range emits FULLY_CONNECTED v12,
newer than the app's TFLite 2.14 runtime is known to load. float16 keeps every op at
v1 (plus DEQUANTIZE v3) at half the original size. Checked against the original
float model: same top label on all test inputs, max probability difference ~0.03.
"""
import sys
import tensorflow as tf

src, out = sys.argv[1], sys.argv[2]
conv = tf.lite.TFLiteConverter.from_saved_model(src)
conv.optimizations = [tf.lite.Optimize.DEFAULT]
conv.target_spec.supported_types = [tf.float16]
open(out, "wb").write(conv.convert())
