LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
 
# Here we give our module name and source file(s)

WISH_MODULES = $(LOCAL_PATH)/wish-c99/deps/mbedtls/library $(LOCAL_PATH)/wish-c99/src $(LOCAL_PATH)/wish-c99/deps/ed25519/src $(LOCAL_PATH)/wish-c99/deps/wish-rpc-c99/src $(LOCAL_PATH)/wish-c99/deps/bson
WISH_CORE_VERSION_STRING = $(shell cd wish-c99; git describe --abbrev=4 --dirty --always --tags)

WISH_SRC := 
LOCAL_MODULE    := wish
LOCAL_SRC_FILES := android_wish.c jni_core_service_ipc.c jni_utils.c wish-c99/port/android/event.c $(foreach sdir,$(WISH_MODULES),$(wildcard $(sdir)/*.c))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/wish-c99/deps/mbedtls/include $(LOCAL_PATH)/wish-c99/src $(LOCAL_PATH)/wish-c99/deps/ed25519/src $(LOCAL_PATH)/wish-c99/deps/wish-rpc-c99/src $(LOCAL_PATH)/wish-c99/wish_app $(LOCAL_PATH)/wish-c99/deps/bson $(LOCAL_PATH)/wish-c99/deps/uthash/include
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -O2 -Wall -Wno-pointer-sign -Werror -fvisibility=hidden -Wno-unused-variable -Wno-unused-function
LOCAL_CFLAGS += -DWISH_CORE_VERSION_STRING=\"$(WISH_CORE_VERSION_STRING)\"
LOCAL_CFLAGS += -DRELEASE_BUILD
#LOCAL_CFLAGS += -flto -fwhole-program #this causes build problem for arm64-v8a

#Put each function in own section, so that linker can discard unused code
LOCAL_CFLAGS += -ffunction-sections -fdata-sections 
#instruct linker to discard unsused code:
LOCAL_LDFLAGS += -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto

NDK_LIBS_OUT=jniLibs

include $(BUILD_SHARED_LIBRARY)

