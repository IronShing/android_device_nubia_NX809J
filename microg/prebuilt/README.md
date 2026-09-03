# microg/prebuilt/ — upstream microG release binaries (not committed)

All three are the microG project's **unmodified** release APKs, Apache-2.0, signed with microG's
own key (SHA-256 `9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165`). They are
not committed because GmsCore is 108 MB (GitHub's per-file limit is 100 MB) — `device.mk` stops
the build with a pointer here when they are missing. Download from
https://github.com/microg/GmsCore/releases (default flavor, **not** the `-hw` Huawei assets) and
place them under this directory with these exact names:

| File | Package | Version | sha256 |
|---|---|---|---|
| `MicroGGmsCore.apk` | `com.google.android.gms` | 0.3.16.252432 (252432032) | `169a53df557e6577322e7cc8aa3389cb82b3d6408dd162433cc94f1d084a73d4` |
| `MicroGCompanion.apk` | `com.android.vending` | 0.3.16.40226 (84022632) | `638f712d267bb9bc57311acbc49e769932bdea9f7fdda26a6ea98b921e632916` |
| `MicroGGsfProxy.apk` | `com.google.android.gsf` | v0.1.0 (8) | `86891b174301f06a1c84187b545a0a2a57044c6b768f3e84e865908743349692` |

Do not re-sign or rebuild them — see `../Android.bp` for why (`preprocessed: true`, signature
allowlist in `ComputerEngine.isMicrogSigned()`).
