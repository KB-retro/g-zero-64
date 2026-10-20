/* port/gdx_android_libc.h -- force-included into the port's C sources on Android only.
 *
 * Bionic's <strings.h> turns bcopy/bzero into macros over overloaded inline functions with
 * strict pointer parameters, so the decomp's N64-style calls (integer addresses, int lengths)
 * fail overload resolution. Route them to plain functions instead (defined in shims.c). */
#ifndef GDX_ANDROID_LIBC_H
#define GDX_ANDROID_LIBC_H

#include <stddef.h>
#include <strings.h>

#undef bcopy
#undef bzero
#define bcopy gdx_bcopy
#define bzero gdx_bzero
void gdx_bcopy(const void* src, void* dst, size_t n);
void gdx_bzero(void* p, size_t n);

#endif
