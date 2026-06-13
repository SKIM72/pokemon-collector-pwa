# Image embedding model

`mobilenet_v3_small.tflite` is the official MediaPipe Image Embedder MobileNetV3
Small float32 model:

https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite

Both the Android scanner and `scripts/index_tcgdex_cards.py` must use this exact
model. Embeddings produced by different models cannot be compared reliably.
