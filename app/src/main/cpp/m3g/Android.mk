LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := javam3g
LOCAL_CFLAGS    := -O3 -DM3G_TARGET_ANDROID #-DM3G_DEBUG -DM3G_GL_ES_1_1
LOCAL_CXXFLAGS  := $(LOCAL_CFLAGS)
LOCAL_LDLIBS    := -llog -lEGL -lGLESv1_CM -lz -ljnigraphics
LOCAL_C_INCLUDES := $(LOCAL_PATH)/inc/
LOCAL_SRC_FILES := \
	CSynchronization.cpp \
	m3g_android_java_api.cpp \
	src/m3g_core.c \
	src/m3g_android.cpp \
	src/m3g_android_gl.cpp

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_ARM_NEON := false
endif

# Don't strip debug builds
ifeq ($(NDK_DEBUG),1)
    cmd-strip :=
endif

include $(BUILD_SHARED_LIBRARY)
