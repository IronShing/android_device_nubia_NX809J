# ReFra — personal-build gallery (not shipped)

`ReFra.apk` is **gitignored** (`*.apk` in the device `.gitignore`) and is **never** part of a
published kit. It is pulled in only when `NX809J_PERSONAL=true` — see the gate in `device.mk`.

## What this file is

| | |
|---|---|
| upstream | <https://github.com/IacobIonut01/ReFra> (Apache-2.0) |
| package | `com.dot.gallery` |
| release | 5.1.3, `ReFra-5.1.3-513004-offline-WithML-arm64-v8a-release.apk` (2026-08-29) |
| size | 369 574 778 B (352 MiB) |
| md5 | `59c3d7fc7aad5a07493136727ee0b716` |
| signature | author's, **v2 scheme only** — preserved verbatim (`presigned` + `preprocessed`) |
| minSdk / targetSdk | 29 / 37 |

**offline** = built without the Immich / Nextcloud / ownCloud / WebDAV / SMB clients.
**WithML** = ships the on-device ONNX models. Those models are what make it large:

| asset | size | what it does |
|---|---|---|
| `arcface.onnx` | 137 MB | face recognition / people grouping |
| `visual_quant.onnx` | 89 MB | CLIP image encoder (semantic search) |
| `textual_quant.onnx` | 64 MB | CLIP text encoder |
| `mobile_sam_image_encoder.onnx` | 28 MB | Segment-Anything encoder (subject cutout) |
| `sam_mask_decoder_single.onnx` | 17 MB | SAM mask decoder |
| `lib/arm64-v8a/*.so` | 71 MB | onnxruntime (31 MB), libheif/x265/aom/dav1d/jxl/raw codecs |

## Refresh

```sh
./fetch.sh 5.1.3          # downloads the same variant, verifies nothing else changed shape
```
Then re-read the `zipinfo` assertion below before trusting a new upload.

## Why this needs no `skip_preprocessed_apk_checks`

A preinstalled app on a read-only partition never gets the install-time step that unpacks
`lib/arm64-v8a/*.so` to `/data`, so an ordinary upstream APK (`extractNativeLibs=true`, deflated
libs) crashes on the first `dlopen` — that is exactly what happened to preinstalled Shizuku here.
ReFra is built the other way: `extractNativeLibs=false` with every `.so` **stored uncompressed**,
so they are mmap'd straight out of the APK. `check_prebuilt_presigned_apk.py` therefore passes on
its own merits. Re-verify after any version bump:

```sh
unzip -v ReFra.apk | awk '$0 ~ /\.so$/ {print $3}' | sort -u     # must print only "Stored"
aapt2 dump xmltree ReFra.apk --file AndroidManifest.xml | grep extractNativeLibs   # must be false
```

If a future release flips either of those, do **not** silence the check — ship the `.so` under
`/product/app/ReFra/lib/arm64/` instead.

## Do not promote this to a shipping variant

352 MB preinstalled in `/product` is not removable by the user. For the public kits the decision
(2026-09-10) is to leave the gallery as Glimpse + Gallery2 and, if anything, link the ReFra release
so people can install it themselves. Gallery2 is **not** redundant with Glimpse: it has no launcher
activity and exists to serve `EDIT` / `com.android.camera.action.CROP` / `TRIM`.
