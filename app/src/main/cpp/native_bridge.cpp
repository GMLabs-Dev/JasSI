#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gmlabs_jarvis_NativeBridge_backendName(JNIEnv* env, jobject) {
    return env->NewStringUTF("Hexagon NPU bridge placeholder");
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gmlabs_jarvis_NativeBridge_nativeGenerate(
    JNIEnv* env, jobject, jstring, jstring) {
    return env->NewStringUTF(
        "The selected local model is downloaded, but native inference is not "
        "enabled in this build. Add the llama.cpp/Qualcomm backend here.");
}
