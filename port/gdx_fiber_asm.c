/* port/gdx_fiber_asm.c -- hand-written context switch backend for gdx_fiber.h.
 *
 * Android's bionic libc has no getcontext/makecontext/swapcontext, so the ucontext backend cannot
 * build there. This backend saves only the callee-saved registers on the running fiber's own stack
 * and swaps stack pointers -- the "~40-line asm switch" gdx_fiber_ucontext.c mentions -- which also
 * skips the sigprocmask syscall swapcontext makes on every switch.
 *
 * Supported: aarch64 (AAPCS64) and x86-64 (System V). The x86-64 path exists so the backend can be
 * exercised on a Linux desktop build (-DGDX_FIBER_ASM=ON); Android ships arm64-v8a only.
 *
 * Stacks are mmap'd with a PROT_NONE guard page at the low end, as in the ucontext backend.
 */
#include "gdx_fiber.h"

#include <stdint.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <pthread.h>
#include <unistd.h>

#if !defined(__aarch64__) && !defined(__x86_64__)
#error "gdx_fiber_asm.c supports aarch64 and x86-64 only"
#endif

#ifndef MAP_STACK
#define MAP_STACK 0
#endif

struct GdxFiber {
    void*         sp;         /* saved stack pointer while not running */
    void*         mapping;    /* mmap base (guard page + usable stack); NULL for host */
    size_t        mappingLen;
    GdxFiberEntry entry;
    void*         arg;
};

/* Saves the callee-saved registers on the current stack, stores the stack pointer in *saveSp,
 * then loads newSp and restores the registers found there. */
void gdx_fiber_asm_swap(void** saveSp, void* newSp);
/* First code a new fiber runs: moves the fiber pointer from a callee-saved register into the first
 * argument register and calls gdx_fiber_asm_start. */
void gdx_fiber_asm_thunk(void);

static __thread GdxFiber* sCurrent = NULL;

#if defined(__aarch64__)
/* Frame: x19..x28, x29, x30, d8..d15 = 20 slots, 160 bytes (keeps sp 16-byte aligned). */
#define GDX_FIBER_FRAME_SLOTS 20
__asm__(
    ".text\n"
    ".p2align 4\n"
    ".globl gdx_fiber_asm_swap\n"
    ".hidden gdx_fiber_asm_swap\n"
    ".type gdx_fiber_asm_swap, %function\n"
    "gdx_fiber_asm_swap:\n"
    "    sub sp, sp, #160\n"
    "    stp x19, x20, [sp, #0]\n"
    "    stp x21, x22, [sp, #16]\n"
    "    stp x23, x24, [sp, #32]\n"
    "    stp x25, x26, [sp, #48]\n"
    "    stp x27, x28, [sp, #64]\n"
    "    stp x29, x30, [sp, #80]\n"
    "    stp d8,  d9,  [sp, #96]\n"
    "    stp d10, d11, [sp, #112]\n"
    "    stp d12, d13, [sp, #128]\n"
    "    stp d14, d15, [sp, #144]\n"
    "    mov x9, sp\n"
    "    str x9, [x0]\n"
    "    mov sp, x1\n"
    "    ldp x19, x20, [sp, #0]\n"
    "    ldp x21, x22, [sp, #16]\n"
    "    ldp x23, x24, [sp, #32]\n"
    "    ldp x25, x26, [sp, #48]\n"
    "    ldp x27, x28, [sp, #64]\n"
    "    ldp x29, x30, [sp, #80]\n"
    "    ldp d8,  d9,  [sp, #96]\n"
    "    ldp d10, d11, [sp, #112]\n"
    "    ldp d12, d13, [sp, #128]\n"
    "    ldp d14, d15, [sp, #144]\n"
    "    add sp, sp, #160\n"
    "    ret\n"
    ".size gdx_fiber_asm_swap, .-gdx_fiber_asm_swap\n"
    "\n"
    ".p2align 4\n"
    ".globl gdx_fiber_asm_thunk\n"
    ".hidden gdx_fiber_asm_thunk\n"
    ".type gdx_fiber_asm_thunk, %function\n"
    "gdx_fiber_asm_thunk:\n"
    "    mov x29, #0\n"
    "    mov x0, x19\n"
    "    bl gdx_fiber_asm_start\n"
    "    brk #0\n"
    ".size gdx_fiber_asm_thunk, .-gdx_fiber_asm_thunk\n");
#else
/* Frame (low to high): r15, r14, r13, r12, rbx, rbp, return address = 7 slots. */
#define GDX_FIBER_FRAME_SLOTS 7
__asm__(
    ".text\n"
    ".p2align 4\n"
    ".globl gdx_fiber_asm_swap\n"
    ".hidden gdx_fiber_asm_swap\n"
    ".type gdx_fiber_asm_swap, @function\n"
    "gdx_fiber_asm_swap:\n"
    "    pushq %rbp\n"
    "    pushq %rbx\n"
    "    pushq %r12\n"
    "    pushq %r13\n"
    "    pushq %r14\n"
    "    pushq %r15\n"
    "    movq %rsp, (%rdi)\n"
    "    movq %rsi, %rsp\n"
    "    popq %r15\n"
    "    popq %r14\n"
    "    popq %r13\n"
    "    popq %r12\n"
    "    popq %rbx\n"
    "    popq %rbp\n"
    "    ret\n"
    ".size gdx_fiber_asm_swap, .-gdx_fiber_asm_swap\n"
    "\n"
    ".p2align 4\n"
    ".globl gdx_fiber_asm_thunk\n"
    ".hidden gdx_fiber_asm_thunk\n"
    ".type gdx_fiber_asm_thunk, @function\n"
    "gdx_fiber_asm_thunk:\n"
    "    xorl %ebp, %ebp\n"
    "    movq %rbx, %rdi\n"
    "    andq $-16, %rsp\n"
    "    call gdx_fiber_asm_start\n"
    "    ud2\n"
    ".size gdx_fiber_asm_thunk, .-gdx_fiber_asm_thunk\n");
#endif

__attribute__((visibility("hidden"), noreturn, used)) void gdx_fiber_asm_start(GdxFiber* f);

void gdx_fiber_asm_start(GdxFiber* f) {
    f->entry(f->arg);
    /* Entry is not expected to return (the decomp threads reschedule through __osCleanupThread);
     * there is no context to return to. */
    abort();
}

GdxFiber* gdx_fiber_convert_thread(void) {
    GdxFiber* f = (GdxFiber*) calloc(1, sizeof(*f));
    if (f == NULL) {
        return NULL;
    }
    sCurrent = f;
    return f;
}

GdxFiber* gdx_fiber_create(GdxFiberEntry entry, void* arg, size_t stackSize) {
    if (stackSize == 0) {
        stackSize = 1024u * 1024u;
    }
    long pageSc = sysconf(_SC_PAGESIZE);
    size_t page = (pageSc > 0) ? (size_t) pageSc : 4096u;
    size_t usable = (stackSize + page - 1u) & ~(page - 1u);
    size_t total = usable + page;

    GdxFiber* f = (GdxFiber*) calloc(1, sizeof(*f));
    if (f == NULL) {
        return NULL;
    }
    void* mem = mmap(NULL, total, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_STACK, -1, 0);
    if (mem == MAP_FAILED) {
        free(f);
        return NULL;
    }
    (void) mprotect(mem, page, PROT_NONE); /* best effort guard page */

    f->mapping = mem;
    f->mappingLen = total;
    f->entry = entry;
    f->arg = arg;

    /* Build the frame gdx_fiber_asm_swap restores: every register zero except the fiber pointer
     * (x19 / rbx) and the return address (x30 / the ret slot), which is the thunk. */
    uintptr_t top = ((uintptr_t) mem + total) & ~(uintptr_t) 15;
    uint64_t* frame = (uint64_t*) (top - 16u * 4u) - GDX_FIBER_FRAME_SLOTS;
    for (int i = 0; i < GDX_FIBER_FRAME_SLOTS; i++) {
        frame[i] = 0;
    }
#if defined(__aarch64__)
    frame[0] = (uint64_t) (uintptr_t) f;                    /* x19 */
    frame[11] = (uint64_t) (uintptr_t) gdx_fiber_asm_thunk; /* x30 */
#else
    frame[4] = (uint64_t) (uintptr_t) f;                    /* rbx */
    frame[6] = (uint64_t) (uintptr_t) gdx_fiber_asm_thunk;  /* return address */
#endif
    f->sp = frame;
    return f;
}

void gdx_fiber_switch(GdxFiber* to) {
    GdxFiber* from = sCurrent;
    if (from == to) {
        return;
    }
    sCurrent = to;
    gdx_fiber_asm_swap(&from->sp, to->sp);
}

unsigned long gdx_fiber_current_thread_id(void) {
#ifdef SYS_gettid
    return (unsigned long) syscall(SYS_gettid);
#else
    return (unsigned long) (uintptr_t) pthread_self();
#endif
}
