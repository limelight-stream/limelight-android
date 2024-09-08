LOCAL_PATH := $(call my-dir)
SUB_PROJECTS := $(call all-subdir-makefiles)

include $(LOCAL_PATH)/libusb/android/jni/libusb.mk
include $(SUB_PROJECTS)