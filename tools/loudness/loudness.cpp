// loudness — attach a global LoudnessEnhancer (makeup gain) to the primary audio
// output so speaker media is louder. EvoX's AIDL effect framework won't load the QTI
// volume_listener from config, so we add an AOSP LoudnessEnhancer (has a built-in
// limiter) as a system-wide post-processing effect. Gain (mB) via argv[1] or
// persist.sys.loudness_gain_mb (default 800 = +8 dB).
//
// Attaches to AUDIO_SESSION_OUTPUT_MIX (global). Requires MODIFY_AUDIO_ROUTING — run
// from a context that holds it (audioserver / system). Keeps the effect alive by
// blocking forever (effect exists while the AudioEffect object lives).

#include <media/AudioEffect.h>
#include <system/audio_effects/effect_loudnessenhancer.h>
#include <binder/ProcessState.h>
#include <binder/IPCThreadState.h>
#include <android/content/AttributionSourceState.h>
#include <utils/Log.h>
#include <cutils/properties.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>

using namespace android;

int main(int argc, char** argv) {
    int32_t gain_mb = 800;
    char prop[PROPERTY_VALUE_MAX];
    if (property_get("persist.sys.loudness_gain_mb", prop, "") > 0) gain_mb = atoi(prop);
    if (argc > 1) gain_mb = atoi(argv[1]);
    if (gain_mb > 1000) gain_mb = 1000;   // hard cap at +10 dB (volume-increase ceiling)
    if (gain_mb < 0) gain_mb = 0;
    fprintf(stderr, "loudness: target gain = %d mB\n", gain_mb);

    ProcessState::self()->startThreadPool();

    content::AttributionSourceState attr;
    attr.uid = getuid();
    attr.pid = getpid();
    attr.packageName = std::optional<std::string>("loudness");

    sp<AudioEffect> effect = new AudioEffect(attr);
    status_t st = effect->set(
        FX_IID_LOUDNESS_ENHANCER,      // type
        nullptr,                        // uuid (any impl of this type)
        0,                              // priority
        nullptr, nullptr,               // callback, user
        AUDIO_SESSION_OUTPUT_MIX,       // global output-mix session
        AUDIO_IO_HANDLE_NONE,           // io
        AudioDeviceTypeAddr(),          // device
        false, false);
    if (st != NO_ERROR) { fprintf(stderr, "AudioEffect.set failed: %d\n", st); return 1; }
    st = effect->initCheck();
    if (st != NO_ERROR && st != ALREADY_EXISTS) { fprintf(stderr, "initCheck failed: %d\n", st); return 1; }

    // set target gain param
    uint32_t buf32[sizeof(effect_param_t)/sizeof(uint32_t) + 2];
    effect_param_t *p = (effect_param_t*)buf32;
    p->psize = sizeof(int32_t);
    p->vsize = sizeof(int32_t);
    *(int32_t*)p->data = LOUDNESS_ENHANCER_PARAM_TARGET_GAIN_MB;
    *((int32_t*)p->data + 1) = gain_mb;
    st = effect->setParameter(p);
    fprintf(stderr, "setParameter -> %d (status %d)\n", st, p->status);

    st = effect->setEnabled(true);
    fprintf(stderr, "setEnabled -> %d\n", st);
    if (st != NO_ERROR) { fprintf(stderr, "enable failed\n"); return 1; }

    fprintf(stderr, "loudness: LoudnessEnhancer attached to global output, +%d mB. holding.\n", gain_mb);
    for (;;) pause();  // keep the effect object alive
    return 0;
}
