# NX809J self-hosted CI

`workflows/build.yml` rebuilds the ROM on a **self-hosted runner** on your build
machine and publishes a GitHub Release. It operates on the **existing tree** (no 268 GB
checkout/sync per run) and runs `patches/apply-patches.sh` first so the out-of-tree
fixes are always present.

## Why self-hosted (not GitHub-hosted)
GitHub-hosted runners cap at ~14 GB disk / 6 h — a LOS build needs hundreds of GB and
hours. So the runner lives on **your** machine, where the tree + native build env
already work (we build from the host shell directly).

## One-time setup (you — needs GitHub access)
1. **Register the runner** on the repo that hosts this workflow
   (`IronShing/android_device_nubia_NX809J`):
   Settings → Actions → Runners → **New self-hosted runner** → Linux. Run the shown
   `./config.sh` **as the `beast` user** (so it inherits the tree + build deps), and
   when it asks for labels add **`nx809j`** (the workflow targets `[self-hosted, nx809j]`).
   - Run it as a service so it survives logout: `sudo ./svc.sh install beast && sudo ./svc.sh start`
     (or `./run.sh` in a `tmux`/lingering session).
2. **(optional) repo/path overrides** — if your paths differ from the defaults, set repo
   *Variables* (Settings → Actions → Variables): `LINEAGE_TREE`, `SUPER_SCRIPT`, `SUPER_IMG`.
3. **Releases permission** — Settings → Actions → General → Workflow permissions →
   **Read and write** (so `GITHUB_TOKEN` can publish releases).
4. **Trigger** — Actions → *Build NX809J* → **Run workflow**. Leave `reposync` off to
   build the tree as-is; turn it on to pull updates first. Set `target` to
   `productimage systemextimage` for a fast incremental, or leave empty for a full ROM.

## Artifact size (important)
The full `super` is ~19 GB raw. GitHub Releases cap at **2 GB per file**, so the workflow
zstd-compresses and **splits** it (`super.img.zst.part00`, `…part01`, …). Reassemble:
```
cat super.img.zst.part* | zstd -d -o super.img
```
The boot-chain images (boot/init_boot/vendor_boot/dtbo/vbmeta) are small and upload
whole. If you'd rather host the big super elsewhere, drop the split/release steps and
`scp`/rclone it from `$SUPER_IMG`.

## Docker / podman (optional — reproducibility, do later)
Native-on-host is the default because it already works. To isolate the build env
(adriano's "self-host + Docker"), wrap only the **Build** step in a container that
bind-mounts the tree — on Silverblue use podman:
```
podman run --rm -v "$LINEAGE":"$LINEAGE" -w "$LINEAGE" <aosp-build-image> \
  bash -lc 'source build/envsetup.sh && unset -f grep; lunch lineage_NX809J-bp4a-userdebug && m -j6'
```
This isn't required for a working pipeline; add it only when you want byte-for-byte
reproducible envs. (The 268 GB tree stays on the host and is bind-mounted, not copied.)

## What this gives you
Press one button → rebuilt ROM + boot images published to GitHub, source already in the
repos. No more shipping the 268 GB folder. Signing stays test-keys (userdebug) for now;
for release-keys, add the keys as repo secrets and a signing step.
