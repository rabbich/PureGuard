PureGuard assets directory.
Place `nsfw.tflite` (MobileNetV2 224x224 RGB, 5 outputs: Drawing, Hentai, Neutral, Porn, Sexy) in this folder (`app/src/main/assets/nsfw.tflite`).
The GitHub Actions workflow (`.github/workflows/build-apk.yml`) automatically downloads `nsfw.tflite` into this folder prior to building the APK.
If `nsfw.tflite` is not present locally, `NsfwClassifier` gracefully falls back to Layer 1 (Text, URL & App Guard) without crashing.
