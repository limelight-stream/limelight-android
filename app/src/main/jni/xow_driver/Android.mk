# Android.mk for xbox wireless driver
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE    := xow-driver
LOCAL_SRC_FILES := \
    xow_driver_jni.cpp \
    dongle/firmware.cpp \
    dongle/usb.cpp \
    dongle/mt76.cpp \
    dongle/dongle.cpp \
    utils/log.cpp \
    controller/controller.cpp \
    controller/gip.cpp

LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LIBUSB_ROOT_ABS)
LOCAL_SHARED_LIBRARIES += libusb1.0
LOCAL_LDLIBS    := -llog

ifeq ($(NDK_DEBUG),1)
LOCAL_CFLAGS += -D_DEBUG
endif
include $(BUILD_SHARED_LIBRARY)


