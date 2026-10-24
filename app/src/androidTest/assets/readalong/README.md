# Speech fixture

`jfk.wav` is the short JFK inaugural-speech sample distributed by whisper.cpp v1.9.4:
https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/samples/jfk.wav

SHA-256: 59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e

The speech is a public-domain US presidential address. The file is included only in the instrumentation-test APK, never the application APK. The model is downloaded separately into ignored build storage and installed in the app's private no-backup directory for testing.

The sentence-start reference is approximately 0.32 seconds, as shown in the upstream word-timestamp example. This short fixture tests the decoding/JNI/matching integration; it cannot establish a general audiobook accuracy percentage.
