# Android.mk for moonlight-core and binding
MY_LOCAL_PATH := $(call my-dir)

include $(call all-subdir-makefiles)

LOCAL_PATH := $(MY_LOCAL_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE    := moonlight-core

# The protocol library the whole repo shares (the monorepo's moonlight-common-c)
COMMON_C := ../../../../../../moonlight-common-c

LOCAL_SRC_FILES := $(COMMON_C)/src/AudioStream.c \
                   $(COMMON_C)/src/ByteBuffer.c \
                   $(COMMON_C)/src/Connection.c \
                   $(COMMON_C)/src/ConnectionTester.c \
                   $(COMMON_C)/src/ControlStream.c \
                   $(COMMON_C)/src/FakeCallbacks.c \
                   $(COMMON_C)/src/InputStream.c \
                   $(COMMON_C)/src/LinkedBlockingQueue.c \
                   $(COMMON_C)/src/Misc.c \
                   $(COMMON_C)/src/Platform.c \
                   $(COMMON_C)/src/PlatformCrypto.c \
                   $(COMMON_C)/src/PlatformSockets.c \
                   $(COMMON_C)/src/RtpAudioQueue.c \
                   $(COMMON_C)/src/RtpVideoQueue.c \
                   $(COMMON_C)/src/RtspConnection.c \
                   $(COMMON_C)/src/RtspParser.c \
                   $(COMMON_C)/src/SdpGenerator.c \
                   $(COMMON_C)/src/SimpleStun.c \
                   $(COMMON_C)/src/VideoDepacketizer.c \
                   $(COMMON_C)/src/VideoStream.c \
                   $(COMMON_C)/nanors/rs.c \
                   $(COMMON_C)/nanors/deps/obl/oblas_common.c \
                   $(COMMON_C)/nanors/deps/obl/oblas_lite.c \
                   $(COMMON_C)/enet/callbacks.c \
                   $(COMMON_C)/enet/compress.c \
                   $(COMMON_C)/enet/host.c \
                   $(COMMON_C)/enet/list.c \
                   $(COMMON_C)/enet/packet.c \
                   $(COMMON_C)/enet/peer.c \
                   $(COMMON_C)/enet/protocol.c \
                   $(COMMON_C)/enet/unix.c \
                   $(COMMON_C)/enet/win32.c \
                   simplejni.c \
                   callbacks.c \
                   minisdl.c

LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(COMMON_C)/enet/include \
                    $(LOCAL_PATH)/$(COMMON_C)/nanors \
                    $(LOCAL_PATH)/$(COMMON_C)/nanors/deps \
                    $(LOCAL_PATH)/$(COMMON_C)/nanors/deps/obl \
                    $(LOCAL_PATH)/$(COMMON_C)/src

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