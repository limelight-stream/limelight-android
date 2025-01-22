# Android.mk for moonlight-core and binding
MY_LOCAL_PATH := $(call my-dir)

include $(call all-subdir-makefiles)

LOCAL_PATH := $(MY_LOCAL_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE    := moonlight-core

LOCAL_SRC_FILES := moonlight-common-c/src/AudioStream.c \
                   moonlight-common-c/src/ByteBuffer.c \
                   moonlight-common-c/src/Connection.c \
                   moonlight-common-c/src/ConnectionTester.c \
                   moonlight-common-c/src/ControlStream.c \
                   moonlight-common-c/src/FakeCallbacks.c \
                   moonlight-common-c/src/InputStream.c \
                   moonlight-common-c/src/LinkedBlockingQueue.c \
                   moonlight-common-c/src/Misc.c \
                   moonlight-common-c/src/Platform.c \
                   moonlight-common-c/src/PlatformCrypto.c \
                   moonlight-common-c/src/PlatformSockets.c \
                   moonlight-common-c/src/RtpAudioQueue.c \
                   moonlight-common-c/src/RtpVideoQueue.c \
                   moonlight-common-c/src/RtspConnection.c \
                   moonlight-common-c/src/RtspParser.c \
                   moonlight-common-c/src/SdpGenerator.c \
                   moonlight-common-c/src/SimpleStun.c \
                   moonlight-common-c/src/VideoDepacketizer.c \
                   moonlight-common-c/src/VideoStream.c \
                   moonlight-common-c/reedsolomon/rs.c \
                   moonlight-common-c/enet/callbacks.c \
                   moonlight-common-c/enet/compress.c \
                   moonlight-common-c/enet/host.c \
                   moonlight-common-c/enet/list.c \
                   moonlight-common-c/enet/packet.c \
                   moonlight-common-c/enet/peer.c \
                   moonlight-common-c/enet/protocol.c \
                   moonlight-common-c/enet/unix.c \
                   moonlight-common-c/enet/win32.c \
                   simplejni.c \
                   callbacks.c \
                   minisdl.c \


LOCAL_C_INCLUDES := $(LOCAL_PATH)/moonlight-common-c/enet/include \
                    $(LOCAL_PATH)/moonlight-common-c/reedsolomon \
                    $(LOCAL_PATH)/moonlight-common-c/src \

LOCAL_CFLAGS := -DHAS_SOCKLEN_T=1 -DLC_ANDROID -DHAVE_CLOCK_GETTIME=1

ifeq ($(NDK_DEBUG),1)
LOCAL_CFLAGS += -DLC_DEBUG
endif

LOCAL_LDLIBS := -llog

LOCAL_STATIC_LIBRARIES := libopus libssl libcrypto cpufeatures
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL

LOCAL_BRANCH_PROTECTION := standard

include $(BUILD_SHARED_LIBRARY)

$(call import-module,android/cpufeatures)