/*
 * Universal exec reroute hook (LD_PRELOAD).
 *
 * Problem: Android refuses direct exec of app-data ELF binaries in several
 * situations — Android 15+ (targetSdk 35+), vendor W^X hardening (Huawei/EMUI
 * rejects executables that are also writable), etc. The engine (node) spawns
 * many child processes (plugins, bash, tools), and each one hits the same
 * wall, so the engine never fully comes up.
 *
 * Solution: intercept the execve family and reroute ELF executions through
 * /system/bin/linker64 — the system linker loads the binary exactly like a
 * native library (dlopen path), which Android permits for app data on every
 * version and vendor. Scripts (shebang) and non-ELF files exec natively.
 * Unlike the snapshot's termux-exec hook, this one applies to every SELinux
 * domain instead of a hardcoded whitelist (untrusted_app_25/27).
 *
 * The LD_PRELOAD environment variable propagates to every process loaded via
 * linker64, so the reroute covers the whole process tree.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Overridable at compile time (-DLINKER=...) so tests can point the reroute
 * at a fake linker without touching the production default. */
#ifndef LINKER
#define LINKER "/system/bin/linker64"
#endif

/* Empty argv fallback for POSIX-legal execv(path, NULL) callers. */
static char *const EMPTY_ARGV[] = { NULL };

/* Expected ELF machine for this build's ABI: a cross-arch ELF (e.g. an
 * arm64 binary on an x86_64 device) must NOT be rerouted through linker64 —
 * it could never load there, and the native exec path is the only one that
 * could possibly handle it. */
#if defined(__aarch64__)
#define EXPECTED_EM 183 /* EM_AARCH64 */
#elif defined(__x86_64__)
#define EXPECTED_EM 62 /* EM_X86_64 */
#else
#define EXPECTED_EM 0
#endif

/* ELF check with ABI match. O_NONBLOCK: PATH search may hit a FIFO — a
 * blocking open would hang the whole exec call chain (the native exec path
 * fails such files immediately with EACCES). */
static int is_elf(const char *path) {
  unsigned char hdr[20];
  int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
  if (fd < 0) return 0;
  ssize_t n = read(fd, hdr, sizeof(hdr));
  close(fd);
  if (n != (ssize_t)sizeof(hdr)) return 0;
  if (hdr[0] != 0x7f || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F') return 0;
  unsigned short em = (unsigned short)(hdr[18] | (hdr[19] << 8));
  return em == EXPECTED_EM;
}

/* execve(LINKER, [LINKER, path, argv[0], argv[1], ...], envp). The linker
 * treats argv[1] as the ELF to load and hands argv[2:] to the program, so the
 * original argv is preserved verbatim. */
static int exec_via_linker(const char *path, char *const argv[],
                           char *const envp[]) {
  /* POSIX allows execv(path, NULL) (the kernel treats it as an empty argv);
   * the rerouted argv must be well-formed too. The fallback is a global so
   * compiler null-check elimination cannot break it. */
  if (!argv) argv = EMPTY_ARGV;
  size_t argc = 0;
  while (argv[argc]) argc++;
  char **new_argv = (char **)malloc((argc + 3) * sizeof(char *));
  if (!new_argv) {
    errno = ENOMEM;
    return -1;
  }
  new_argv[0] = (char *)LINKER;
  new_argv[1] = (char *)path;
  for (size_t i = 0; i <= argc; i++) new_argv[i + 2] = argv[i];
  int r = (int)syscall(SYS_execve, LINKER, new_argv, envp);
  free(new_argv);
  return r;
}

/* Returns 1 when the caller should fall through to the original syscall,
 * 0 when the reroute already ran. Every reroute failure falls through: the
 * linker can reject binaries the kernel would still exec (no PT_INTERP,
 * corrupt ELF), so the native syscall always gets a chance — the original
 * errno is lost, but the fallback keeps such execs working at all. */
static int maybe_reroute(const char *path, char *const argv[],
                         char *const envp[]) {
  if (!path || strcmp(path, LINKER) == 0) return 1;
  if (!is_elf(path)) return 1;
  exec_via_linker(path, argv ? argv : EMPTY_ARGV, envp);
  return 1;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
  /* glibc's prototype marks path/argv as __nonnull((1,2)); under -O2 GCC
   * would then delete every null check below, so a POSIX-legal
   * execv(path, NULL) call crashed inside the hook (observed: SIGSEGV in
   * the argv walk at -O2, fine at -O0). Reading the parameters through
   * volatile keeps the null checks real. */
  char *const *volatile argv_v = argv;
  const char *volatile path_v = path;
  if (maybe_reroute(path_v ? path_v : "", argv_v ? argv_v : EMPTY_ARGV, envp)) {
    return (int)syscall(SYS_execve, path_v ? path_v : "", argv_v, envp);
  }
  return -1;
}

int execv(const char *path, char *const argv[]) {
  return execve(path, argv, environ);
}

int execvp(const char *file, char *const argv[]) {
  if (strchr(file, '/')) return execve(file, argv, environ);
  const char *path = getenv("PATH");
  if (!path) path = "/system/bin";
  char *copy = strdup(path);
  if (!copy) {
    errno = ENOMEM;
    return -1;
  }
  char *save = NULL;
  int last_errno = ENOENT;
  for (char *dir = strtok_r(copy, ":", &save); dir;
       dir = strtok_r(NULL, ":", &save)) {
    size_t len = strlen(dir) + strlen(file) + 2;
    char *full = (char *)malloc(len);
    if (!full) {
      free(copy);
      errno = ENOMEM;
      return -1;
    }
    snprintf(full, len, "%s/%s", dir, file);
    int r = execve(full, argv, environ);
    int e = errno;
    free(full);
    if (r != -1) { /* execve only returns on failure */
      free(copy);
      return r;
    }
    if (e != ENOENT && e != ENOTDIR) {
      last_errno = e;
      break;
    }
  }
  free(copy);
  errno = last_errno;
  return -1;
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
  if (strchr(file, '/')) return execve(file, argv, envp);
  const char *path = getenv("PATH");
  if (!path) path = "/system/bin";
  char *copy = strdup(path);
  if (!copy) {
    errno = ENOMEM;
    return -1;
  }
  char *save = NULL;
  int last_errno = ENOENT;
  for (char *dir = strtok_r(copy, ":", &save); dir;
       dir = strtok_r(NULL, ":", &save)) {
    size_t len = strlen(dir) + strlen(file) + 2;
    char *full = (char *)malloc(len);
    if (!full) {
      free(copy);
      errno = ENOMEM;
      return -1;
    }
    snprintf(full, len, "%s/%s", dir, file);
    int r = execve(full, argv, envp);
    int e = errno;
    free(full);
    if (r != -1) {
      free(copy);
      return r;
    }
    if (e != ENOENT && e != ENOTDIR) {
      last_errno = e;
      break;
    }
  }
  free(copy);
  errno = last_errno;
  return -1;
}
