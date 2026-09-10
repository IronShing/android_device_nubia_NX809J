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

# org.codeaurora.ims loads these JNI libs at ImsService.setup() (ImsMedia/VT
# init) via System.loadLibrary; without them in /system_ext/lib64 the service
# dies with UnsatisfiedLinkError "libimsmedia_jni.so not found" → crash-loops →
# RescueParty. They only link standard platform libs (no vendor chain).
PRODUCT_COPY_FILES += \
    $(IMS_BLOBS)/lib64/libimsmedia_jni.so:$(SE)/lib64/libimsmedia_jni.so \
    $(IMS_BLOBS)/lib64/libimscamera_jni.so:$(SE)/lib64/libimscamera_jni.so

# IMS VIDEO calling (ViLTE/VT) — orphan-audit fix 2026-09-07. libimsmedia_jni.so
# dlopen()s lib-imsvt.so at VT media init; until now that lib was absent, so the
# vendor VT/ImsRtpService stack had no system-side client and video calls could
# never set up media (voice VoLTE unaffected). lib-imsvt chain from stock .18
# system_ext/lib64 (+ vendor.qti.diaghal-V1-ndk from stock system/lib64): the
# four VT libs, the ImsRtpService client stubs it links, and libdiag_system with
# its diaghal stubs. Closure verified against our system/system_ext/apex libs.
# The ims app (vendor_qtelephony) is already a vendor_hal_imsrtphal_client in the
# stock vendor CIL, so no new sepolicy is expected — verify with denials on VT.
PRODUCT_COPY_FILES += \
    $(IMS_BLOBS)/lib64/lib-imsvt.so:$(SE)/lib64/lib-imsvt.so \
    $(IMS_BLOBS)/lib64/lib-imsvideocodec.so:$(SE)/lib64/lib-imsvideocodec.so \
    $(IMS_BLOBS)/lib64/lib-imsvtutils.so:$(SE)/lib64/lib-imsvtutils.so \
    $(IMS_BLOBS)/lib64/lib-imsvtextutils.so:$(SE)/lib64/lib-imsvtextutils.so \
    $(IMS_BLOBS)/lib64/vendor.qti.imsrtpservice@3.0.so:$(SE)/lib64/vendor.qti.imsrtpservice@3.0.so \
    $(IMS_BLOBS)/lib64/vendor.qti.imsrtpservice@3.1.so:$(SE)/lib64/vendor.qti.imsrtpservice@3.1.so \
    $(IMS_BLOBS)/lib64/vendor.qti.ImsRtpService-V2-ndk.so:$(SE)/lib64/vendor.qti.ImsRtpService-V2-ndk.so \
    $(IMS_BLOBS)/lib64/libdiag_system.so:$(SE)/lib64/libdiag_system.so \
    $(IMS_BLOBS)/lib64/vendor.qti.diaghal@1.0.so:$(SE)/lib64/vendor.qti.diaghal@1.0.so \
    $(IMS_BLOBS)/lib64/vendor.qti.diaghal-V1-ndk.so:$(SE)/lib64/vendor.qti.diaghal-V1-ndk.so

# The grafted QTI IMS/RCS apps declare many <uses-library vendor.qti.ims.*-java>
# (vendor runtime shared libs not modeled in the build system), which trips
# soong's enforce_uses_libraries manifest check. Relax it product-wide — these
# are preprocessed vendor prebuilts that resolve their class-loader context at
# runtime against the vendor libs; AOT verification of that context isn't
# possible here. (Recommended remedy per the manifest_check error itself.)
PRODUCT_BROKEN_VERIFY_USES_LIBRARIES := true

# Build the open-source IMS framework extension (org.codeaurora.ims API surface)
# plus the qti-telephony-{hidl-wrapper,utils} shared libs — these are built from
# source (vendor/codeaurora/telephony) and soong installs both the jar and its
# <library> permission XML to system_ext, so they must NOT also be copied below
# (kati "overriding commands" conflict). qti-telephony-common + the imscmservice
# jars have no soong module, so they stay as prebuilt copies.
PRODUCT_PACKAGES += \
    ims-ext-common \
    qti-telephony-hidl-wrapper \
    qti-telephony-utils
# ims.apk <uses-library> these three shared libs; PMS needs their <library>
# permission XML declared or it fails ("requires unavailable shared library ...")
# → system_server bootloop. The soong prebuilt_etc XML modules install them
# (system_ext for wrapper/utils, product for ims_ext_common); add them explicitly
# since the java_library modules above don't pull their permission XML.
PRODUCT_PACKAGES += \
    qti_telephony_hidl_wrapper.xml \
    qti_telephony_utils.xml \
    ims_ext_common.xml
# QtiTelephony (com.qti.phone, IExtPhone) <uses-library> com.qti.extphone.extphonelib
# — source-buildable (system_ext). Needed by the QTI IMS voice path. Add the jar +
# its <library> permission XML.
PRODUCT_PACKAGES += \
    extphonelib \
    extphonelib.xml

# --- ImsService + QTI telephony apps -----------------------------------------
# APKs are imported as android_app_import modules (see ims/Android.bp) — kati
# rejects .apk in PRODUCT_COPY_FILES ("use BUILD_PREBUILT instead"). The module
# names install to system_ext/{priv-,}app/<name>/. Binding is by package name
# (config_ims_mmtel_package=org.codeaurora.ims), independent of install dir.
# MINIMAL set for VoLTE *voice*: only org.codeaurora.ims (the MMTel ImsService
# that config_ims_mmtel_package binds). Its <uses-library> deps (qti-telephony-
# hidl-wrapper/utils, ims-ext-common) are satisfied above. The other QTI apps were
# DROPPED — they <uses-library> ungrafted vendor IMS libs (vendor.qti.ims.connection,
# .datachannelservice, .uceservice, com.qti.extphone.extphonelib) → PMS
# "unavailable shared library" → system_server bootloop. They're RCS / UCE /
# data-channel / extphone (messaging + carrier extras), NOT needed for VoLTE audio.
# Re-add individually (with their lib chains grafted) if a feature needs them.
# QtiTelephony (com.qti.phone/IExtPhone) + QtiTelephonyService re-added with their
# lib chain (extphonelib + qcrilhook above) — the QTI ext-telephony stack the IMS
# voice path needs to provision MMTel VOICE. (android.uid.qtiphone: presigned,
# self-consistent, no LOS seed.)
# QtiTelephonyService (com.qualcomm.qti.telephonyservice) RE-ADDED 2026-06-18: it
# shares android.uid.qtiphone with QtiTelephony → inherits privileged, and holds
# android.permission.MODIFY_AUDIO_ROUTING. It is THE component that calls
# AudioManager.setParameters("vsid=0x11C05000;call_state=2;call_type=LTE") during a
# VoLTE call, which starts the modem voice session in the QTI audio HAL (AHAL_
# Telephony VoiceStart). Without it the HAL's per-VSID voice session never leaves
# CallState=Default → no VoiceStart → call connects but is SILENT (proven by stock
# golden diff, ~/android/stock_volte_golden). The earlier drop was because its
# MODIFY_AUDIO_ROUTING had no privapp allowlist → system_server fatal; now added to
# telephony_system-ext_privapp-permissions-qti.xml below. Pairs with the GAP-A fix
# use.voice.path.for.pcm.voip=true (product.prop) to give two-way VoLTE audio.
# Its only <uses-library> (qti-telephony-hidl-wrapper) is already grafted above.
PRODUCT_PACKAGES += \
    ims \
    QtiTelephony \
    QtiTelephonyService

# --- QTI telephony framework jars (loaded via the <library> permission XMLs) --
PRODUCT_COPY_FILES += \
    $(IMS_BLOBS)/framework/qti-telephony-common.jar:$(SE)/framework/qti-telephony-common.jar \
    $(IMS_BLOBS)/framework/qcrilhook.jar:$(SE)/framework/qcrilhook.jar \
    $(IMS_BLOBS)/framework/com.qualcomm.qti.imscmservice-V2.0-java.jar:$(SE)/framework/com.qualcomm.qti.imscmservice-V2.0-java.jar \
    $(IMS_BLOBS)/framework/com.qualcomm.qti.imscmservice-V2.1-java.jar:$(SE)/framework/com.qualcomm.qti.imscmservice-V2.1-java.jar \
    $(IMS_BLOBS)/framework/com.qualcomm.qti.imscmservice-V2.2-java.jar:$(SE)/framework/com.qualcomm.qti.imscmservice-V2.2-java.jar

# --- Permissions: shared-lib (<library>) decls + privapp allowlists + feature --
PRODUCT_COPY_FILES += \
    $(IMS_BLOBS)/etc/permissions/android.hardware.telephony.ims.xml:$(SE)/etc/permissions/android.hardware.telephony.ims.xml \
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
