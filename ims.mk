# QTI IMS / VoLTE stack — grafted from the EA/global stock dump (matches this
# device's vendor fingerprint REDMAGIC/NX809J BP2A...). Without an Android-side
# ImsService the framework's ImsResolver finds nothing to bind, IMS never
# registers (imsRegistrationTech=-1), and on this VoLTE-only network (no 2G/3G CS
# fallback) calls connect but carry no voice bearer -> NO AUDIO. The native vendor
# IMS daemons (imsdaemon/ims_rtp_daemon) already run; this adds the framework half.
#
# Components: org.codeaurora.ims (priv-app/ims) = the MmTel ImsService, the QTI
# telephony framework jars (loaded as shared libs via the permission XMLs), the
# QtiTelephony/QtiTelephonyService + RCS/datachannel/uce/voiceactivation apps, and
# ims-ext-common (built from source: vendor/codeaurora/telephony/ims).
#
# Prereqs already in device.mk: persist.dbg.{volte,vt,wfc}_avail_ovr=1.
# Framework binding: config_ims_mmtel_package="org.codeaurora.ims" is set in the
# device overlay (overlay/.../core/res/res/values/config.xml).
#
# Blobs live in ims/blobs/ (committed). NOTE (build/RE): the device ships
# PERMISSIVE, so the IMS apps need no custom sepolicy to run. If a prebuilt APK
# trips dexpreopt/uses-library verification, convert that APK to android_app_import
# (presigned + privileged). Confirm ims.apk's package is org.codeaurora.ims with
# aapt; if it differs, update config_ims_mmtel_package to match.

IMS_BLOBS := $(LOCAL_PATH)/ims/blobs
SE := $(TARGET_COPY_OUT_SYSTEM_EXT)

# Build the open-source IMS framework extension (org.codeaurora.ims API surface).
PRODUCT_PACKAGES += \
    ims-ext-common

# --- ImsService + QTI telephony apps -----------------------------------------
PRODUCT_COPY_FILES += \
    $(IMS_BLOBS)/priv-app/ims/ims.apk:$(SE)/priv-app/ims/ims.apk \
    $(IMS_BLOBS)/priv-app/QtiTelephony/QtiTelephony.apk:$(SE)/priv-app/QtiTelephony/QtiTelephony.apk \
    $(IMS_BLOBS)/priv-app/QualcommVoiceActivation/QualcommVoiceActivation.apk:$(SE)/priv-app/QualcommVoiceActivation/QualcommVoiceActivation.apk \
    $(IMS_BLOBS)/app/QtiTelephonyService/QtiTelephonyService.apk:$(SE)/app/QtiTelephonyService/QtiTelephonyService.apk \
    $(IMS_BLOBS)/app/imssettings/imssettings.apk:$(SE)/app/imssettings/imssettings.apk \
    $(IMS_BLOBS)/app/ImsRcsService/ImsRcsService.apk:$(SE)/app/ImsRcsService/ImsRcsService.apk \
    $(IMS_BLOBS)/app/ImsDataChannelService/ImsDataChannelService.apk:$(SE)/app/ImsDataChannelService/ImsDataChannelService.apk \
    $(IMS_BLOBS)/app/uceShimService/uceShimService.apk:$(SE)/app/uceShimService/uceShimService.apk

# --- QTI telephony framework jars (loaded via the <library> permission XMLs) --
PRODUCT_COPY_FILES += \
    $(IMS_BLOBS)/framework/qti-telephony-common.jar:$(SE)/framework/qti-telephony-common.jar \
    $(IMS_BLOBS)/framework/qti-telephony-hidl-wrapper.jar:$(SE)/framework/qti-telephony-hidl-wrapper.jar \
    $(IMS_BLOBS)/framework/qti-telephony-utils.jar:$(SE)/framework/qti-telephony-utils.jar \
    $(IMS_BLOBS)/framework/com.qualcomm.qti.imscmservice-V2.0-java.jar:$(SE)/framework/com.qualcomm.qti.imscmservice-V2.0-java.jar \
    $(IMS_BLOBS)/framework/com.qualcomm.qti.imscmservice-V2.1-java.jar:$(SE)/framework/com.qualcomm.qti.imscmservice-V2.1-java.jar \
    $(IMS_BLOBS)/framework/com.qualcomm.qti.imscmservice-V2.2-java.jar:$(SE)/framework/com.qualcomm.qti.imscmservice-V2.2-java.jar

# --- Permissions: shared-lib (<library>) decls + privapp allowlists + feature --
PRODUCT_COPY_FILES += \
    $(IMS_BLOBS)/etc/permissions/android.hardware.telephony.ims.xml:$(SE)/etc/permissions/android.hardware.telephony.ims.xml \
    $(IMS_BLOBS)/etc/permissions/qti_telephony_hidl_wrapper.xml:$(SE)/etc/permissions/qti_telephony_hidl_wrapper.xml \
    $(IMS_BLOBS)/etc/permissions/qti_telephony_utils.xml:$(SE)/etc/permissions/qti_telephony_utils.xml \
    $(IMS_BLOBS)/etc/permissions/qcrilhook.xml:$(SE)/etc/permissions/qcrilhook.xml \
    $(IMS_BLOBS)/etc/permissions/com.qualcomm.qti.imscmservice-V2.0-java.xml:$(SE)/etc/permissions/com.qualcomm.qti.imscmservice-V2.0-java.xml \
    $(IMS_BLOBS)/etc/permissions/com.qualcomm.qti.imscmservice-V2.1-java.xml:$(SE)/etc/permissions/com.qualcomm.qti.imscmservice-V2.1-java.xml \
    $(IMS_BLOBS)/etc/permissions/com.qualcomm.qti.imscmservice-V2.2-java.xml:$(SE)/etc/permissions/com.qualcomm.qti.imscmservice-V2.2-java.xml \
    $(IMS_BLOBS)/etc/permissions/telephony_system-ext_privapp-permissions-qti.xml:$(SE)/etc/permissions/telephony_system-ext_privapp-permissions-qti.xml \
    $(IMS_BLOBS)/etc/permissions/privapp-permissions-qti-system-ext.xml:$(SE)/etc/permissions/privapp-permissions-qti-system-ext.xml \
    $(IMS_BLOBS)/etc/permissions/vendor.qti.ims.rcsservice.xml:$(SE)/etc/permissions/vendor.qti.ims.rcsservice.xml \
    $(IMS_BLOBS)/etc/permissions/vendor.qti.imsdatachannel.xml:$(SE)/etc/permissions/vendor.qti.imsdatachannel.xml \
    $(IMS_BLOBS)/etc/permissions/vendor.qti.imsdcservice.xml:$(SE)/etc/permissions/vendor.qti.imsdcservice.xml \
    $(IMS_BLOBS)/etc/permissions/vendor-voicemail-conf.xml:$(SE)/etc/permissions/vendor-voicemail-conf.xml
