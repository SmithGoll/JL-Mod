LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := gles_utils
LOCAL_SRC_FILES := \
	gles1.cpp \
	gles2.cpp \
	utils.cpp \

LOCAL_CPPFLAGS +=
LOCAL_LDLIBS := -llog -lGLESv1_CM -lGLESv2 -ljnigraphics
LOCAL_STATIC_LIBRARIES :=
LOCAL_C_INCLUDES := $(LOCAL_PATH)

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_ARM_NEON := false
endif

# Don't strip debug builds
ifeq ($(NDK_DEBUG),1)
    cmd-strip :=
endif

include $(BUILD_SHARED_LIBRARY)
