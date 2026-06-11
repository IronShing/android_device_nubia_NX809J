LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := fstab.qcom
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := ETC
LOCAL_SRC_FILES := fstab.qcom
LOCAL_MODULE_PATH := $(TARGET_OUT_VENDOR_ETC)
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := init.NX809J.rc
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := ETC
LOCAL_SRC_FILES := init.NX809J.rc
# Install to /odm/etc/init (NOT /vendor/etc/init). Our deployment model flashes
# the LineageOS-built odm partition but keeps the STOCK vendor partition, so a
# vendor-installed rc never reaches the device. init auto-imports /odm/etc/init/*.rc,
# so landing it here is what makes the hardware-init layer (fan/RGB/trigger/haptics
# chown+chmod) actually execute at runtime.
LOCAL_MODULE_PATH := $(TARGET_OUT_ODM_ETC)/init
include $(BUILD_PREBUILT)
