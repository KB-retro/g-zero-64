/* port/gdx_self_exe.h -- readlink("/proc/self/exe") with an Android override.
 *
 * Desktop builds find their portable data folder beside the executable. On Android the game is
 * libmain.so inside app_process, so /proc/self/exe is the zygote binary; the launcher instead
 * exports GDX_APP_DIR (the app's external files folder) and this returns a path inside it, which
 * keeps every "strip the file name to get the exe dir" caller working unchanged.
 *
 * Same contract as readlink: returns the byte count, or -1, and does not NUL-terminate.
 */
#ifndef GDX_SELF_EXE_H
#define GDX_SELF_EXE_H

#include <string.h>
#include <sys/types.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <stdlib.h>
#endif

static inline ssize_t gdx_readlink_self_exe(char* buf, size_t size) {
#ifdef __ANDROID__
    const char* dir = getenv("GDX_APP_DIR");
    if (dir == NULL || dir[0] == '\0') {
        return -1;
    }
    size_t len = strlen(dir) + strlen("/G-Zero64");
    if (len > size) {
        return -1;
    }
    memcpy(buf, dir, strlen(dir));
    memcpy(buf + strlen(dir), "/G-Zero64", strlen("/G-Zero64"));
    return (ssize_t) len;
#else
    return readlink("/proc/self/exe", buf, size);
#endif
}

/* Android installs gdx-extract as lib/arm64/libgdx_extract.so (the only place an app may execute
 * files from) and the launcher exports its path as GDX_EXTRACT_BIN. NULL elsewhere. */
static inline const char* gdx_extract_binary_override(void) {
#ifdef __ANDROID__
    const char* bin = getenv("GDX_EXTRACT_BIN");
    return (bin != NULL && bin[0] != '\0') ? bin : NULL;
#else
    return NULL;
#endif
}

#endif /* GDX_SELF_EXE_H */
