# prebuilt/AndroidAuto/ — Google-signed Android Auto split cluster (not committed)

Google's proprietary release binaries; not redistributed here. `android_auto.mk` builds without
Android Auto (with a warning) when `base.apk` is absent. To include it, obtain the
`com.google.android.projection.gearhead` 17.8.663814 (versionCode 178663814) split set that Play
serves an arm64-v8a / xxhdpi / en device (e.g. from your own Play download or an APK mirror) and
place the four files here. Verify the signer is Google's (apksigner V3.1 signer sha256
`1ca8dcc0…`, lineage from `fdb00c43…`) — head units reject anything else.

| File | sha256 |
|---|---|
| `base.apk` | `018eb188a82836f829b583b40ccbab3293498eb0a6c90051d99149e283334c1f` |
| `split_config.arm64_v8a.apk` | `74278e51addbda5b2e23d3ff3326a316f912ee1f14078460225bafd8f36b69cb` |
| `split_config.en.apk` | `a88d538b413d130d60abca61e97f17066814d900037839be362e1759684c8b82` |
| `split_config.xxhdpi.apk` | `f0c8e672196b0502d19f0cfa08337f2d7f19f85a4a0ba24ac36b49e9b10873f0` |

Signing scripts must leave these Google-signed: pass `-e split_config.arm64_v8a.apk= -e
split_config.en.apk= -e split_config.xxhdpi.apk=` to `sign_target_files_apks` (see
`sign_a17_release.sh`).
