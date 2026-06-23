# Display source-build patches (native-enforcing vendor.img)

hardware/qcom-caf/sm8750/display is NOT a git repo in this tree, so these edits
are saved here (device tree IS git) to survive a repo sync. Re-apply if lost.

Files (dest under hardware/qcom-caf/sm8750/display/):
- core/sdm/libs/utils/formats.cpp   <- formats.cpp   (+float_2_FP16/FP16_2_float/IsFP16ExtendedRange/IsSCRGB)
- core/sdm/include/utils/formats.h  <- formats.h     (+4 decls)
- core/sdm/libs/dal/Android.bp      <- dal_Android.bp (+header_libs:[display_headers])
- hal/Android.bp                    <- hal_Android.bp (display_headers += nx809j_qti_display_uapi)

Plus (in git already): device-tree composer_version=v3_3 pin (vendor_hals.mk),
vendor/nubia prefer:false on libsdmcore/dal/utils, device/nubia/NX809J-kernel
display-uapi headers + header lib.
