//
// Created by xm1994 on 2024/9/1.
//

#include <memory>

#include <jni.h>
#include "dongle/usb.h"
#include "dongle/dongle.h"

#include "utils/log.h"

extern "C"
JNIEXPORT jlong JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_createDriver(JNIEnv *env, jobject thiz, jint fd) {

    auto usbDevice = std::make_unique<UsbDevice>(fd);
    JavaVM *jvm = nullptr;
    jint r = env->GetJavaVM(&jvm);
    if(r != JNI_OK || jvm == nullptr) {
        return -1;
    }
    auto dongle = new Dongle(std::move(usbDevice), env->NewGlobalRef(thiz), jvm);
    return (jlong) dongle;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_startDriver(JNIEnv *env, jobject thiz,
                                                                       jlong handle, jstring fwPath) {
    auto *dongle = (Dongle *) handle;
    jboolean copy = false;
    auto cfwPath = env->GetStringUTFChars(fwPath, &copy);
    auto succ = dongle->start(cfwPath);
    env->ReleaseStringUTFChars(fwPath, cfwPath);
    return succ;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_stopDriver(JNIEnv *env, jobject thiz,
                                                                      jlong handle) {
    auto *dongle = (Dongle *) handle;
    dongle->stop();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_destroyDriver(JNIEnv *env, jobject thiz,
                                                                         jlong handle) {
    auto *dongle = (Dongle *) handle;
    dongle->stop();
    delete dongle;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessController_sendRumble(JNIEnv *env, jobject thiz,
                                                                          jlong handle,
                                                                          jshort low_freq_motor,
                                                                          jshort high_freq_motor) {
    auto *controller = (Controller *) handle;
    controller->inputRumble(low_freq_motor, high_freq_motor);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessController_sendrumbleTriggers(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jlong handle,
                                                                                  jshort left_trigger,
                                                                                  jshort right_trigger) {
    auto *controller = (Controller *) handle;
    controller->inputRumbleTrigger(left_trigger, right_trigger);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessController_registerNative(JNIEnv *env,
                                                                              jobject thiz,
                                                                              jlong handle) {
    JavaVM *jvm = nullptr;
    jint r = env->GetJavaVM(&jvm);
    if(r != JNI_OK || jvm == nullptr) {
        Log::error("GetJavaVM failed");
    }
    auto *controller = (Controller *) handle;
    controller->registerJavaContext(jvm, env->NewGlobalRef(thiz));
}