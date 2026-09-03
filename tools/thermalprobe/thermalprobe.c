// Throwaway probe: dump what the vendor thermal client knows about an algo.
//
// dlopen()s /vendor/lib64/libthermalclient.so rather than linking it, because a
// system_ext binary has no linker namespace that searches /vendor/lib64. Run as
// ROOT from /data/local/tmp with LD_LIBRARY_PATH=/vendor/lib64 -- a root shell
// process is not subject to the app namespace, so the load succeeds there even
// though it would not from a shipped system_ext daemon.
//
// Derived from llvm-objdump of the library (2026-08-27):
//   thermal_client_config_query(name, out) -- name field is strlcpy'd at 20 bytes
//   config entries are 32 bytes: char* / char* / u32 / u32 / void*
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <stdint.h>

typedef int (*qfn)(const char *, void *);

static void dump(const char *tag, const unsigned char *p, int n) {
    printf("%s (%d bytes):\n", tag, n);
    for (int i = 0; i < n; i += 16) {
        printf("  %04x  ", i);
        for (int j = 0; j < 16 && i + j < n; j++) printf("%02x ", p[i + j]);
        printf("  |");
        for (int j = 0; j < 16 && i + j < n; j++)
            printf("%c", (p[i+j] >= 32 && p[i+j] < 127) ? p[i+j] : '.');
        printf("|\n");
    }
}

int main(int argc, char **argv) {
    const char *algo = (argc > 1) ? argv[1] : "SKIN_GPU_MONITOR";

    void *h = dlopen("/vendor/lib64/libthermalclient.so", RTLD_NOW);
    if (!h) { printf("dlopen failed: %s\n", dlerror()); return 1; }
    printf("dlopen ok\n");

    qfn q = (qfn)dlsym(h, "thermal_client_config_query");
    printf("thermal_client_config_query = %p\n", (void *)q);
    if (!q) { printf("dlsym failed: %s\n", dlerror()); return 1; }

    // generous out buffer -- the library memsets ~15.6K internally, so give it room
    unsigned char *out = calloc(1, 65536);
    printf("calling query(\"%s\", %p) ...\n", algo, out);
    int rc = q(algo, out);
    printf("rc = %d\n", rc);

    // the first 32 bytes are the config entry; print a decoded view too
    dump("raw out", out, 256);
    uint64_t p0 = *(uint64_t *)(out + 0x00);
    uint64_t p1 = *(uint64_t *)(out + 0x08);
    uint32_t u10 = *(uint32_t *)(out + 0x10);
    uint32_t u14 = *(uint32_t *)(out + 0x14);
    uint64_t p18 = *(uint64_t *)(out + 0x18);
    printf("entry[0]: 0x00=%p 0x08=%p 0x10=%u 0x14=%u 0x18=%p\n",
           (void*)p0, (void*)p1, u10, u14, (void*)p18);
    if (p0 > 0x1000) printf("  0x00 as string: \"%.32s\"\n", (char *)p0);
    if (p1 > 0x1000) printf("  0x08 as string: \"%.32s\"\n", (char *)p1);
    return 0;
}
