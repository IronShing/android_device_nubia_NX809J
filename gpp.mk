# Copyright (C) 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# --- Qualcomm Game Post Processing (GPP): NPU super-resolution + frame interpolation ---
# The stock "R4 gaming chip" feature. Qualcomm's QSSI half of the stack, taken from the
# stock .18 /system (Qualcomm-generic sm8850 blobs, not ZTE code):
#   libgui QtiSurfaceExtensionGPP (source, frameworks/native nx809j-gpp) -> dlopen
#   libgppextension.so -> binder "vendor.gppservice" (/system/bin/gppservice, class main,
#   user system) -> libgpphexlpsession.so -> vendor.qti.hardware.hexlp.IHexlpService (already
#   running on our vendor). Everything must stay under /system: the labels in
#   device/qcom/sepolicy/generic/private/gppservice.te + file_contexts (already compiled into
#   system_ext policy) are /system/bin/gppservice, and gppservice hard-codes
#   /system/etc/seccomp_policy/gppservice.policy and /system/etc/gpp_app_list.
# libgpphexlpsession needs vendor.qti.hardware.hexlp-V2-ndk.so in /system/lib64 (the vendor copy
# is not visible from the system namespace). libgppvppgfrcplussession.so (VPP path) is NOT shipped:
# it links a DisplayEventReceiver ctor our libgui no longer has, and sm8850 uses HexLP.
# Control: RedMagicControl GamePostProcessing.java speaks the stock MindSyncService property
# protocol (vendor.gpp.frc.enable 0x22/0x21 etc). See ~/lineage-scratch/NX809J_GPP_NOTES.md.

GPP_PATH := $(LOCAL_PATH)/gpp

PRODUCT_COPY_FILES += \
    $(GPP_PATH)/bin/gppservice:$(TARGET_COPY_OUT_SYSTEM)/bin/gppservice \
    $(GPP_PATH)/etc/gppservice.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/gppservice.rc \
    $(GPP_PATH)/etc/gppservice.policy:$(TARGET_COPY_OUT_SYSTEM)/etc/seccomp_policy/gppservice.policy \
    $(GPP_PATH)/etc/gpp_app_list:$(TARGET_COPY_OUT_SYSTEM)/etc/gpp_app_list \
    $(GPP_PATH)/lib64/libgppextension.so:$(TARGET_COPY_OUT_SYSTEM)/lib64/libgppextension.so \
    $(GPP_PATH)/lib64/libgpphexlpsession.so:$(TARGET_COPY_OUT_SYSTEM)/lib64/libgpphexlpsession.so \
    $(GPP_PATH)/lib64/libgpppreprocessing.so:$(TARGET_COPY_OUT_SYSTEM)/lib64/libgpppreprocessing.so \
    $(GPP_PATH)/lib64/libgpptxr.so:$(TARGET_COPY_OUT_SYSTEM)/lib64/libgpptxr.so \
    $(GPP_PATH)/lib64/libMotionEngine.so:$(TARGET_COPY_OUT_SYSTEM)/lib64/libMotionEngine.so \
    $(GPP_PATH)/lib64/libMotionEngineVk.so:$(TARGET_COPY_OUT_SYSTEM)/lib64/libMotionEngineVk.so \
    $(GPP_PATH)/lib64/vendor.qti.hardware.hexlp-V2-ndk.so:$(TARGET_COPY_OUT_SYSTEM)/lib64/vendor.qti.hardware.hexlp-V2-ndk.so

# Makes every app Surface (uid >= 10000) create the GPP extension object; it is inert until
# vendor.gpp.frc.enable is flipped to 0x22 by RedMagicControl.
PRODUCT_SYSTEM_PROPERTIES += \
    vendor.gpp.create_frc_extension=1
