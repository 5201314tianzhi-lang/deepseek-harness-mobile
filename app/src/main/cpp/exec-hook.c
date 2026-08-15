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

#define LINKER "/system/bin/linker64"
#define LINKER_LEN (sizeof(LINKER) - 1)

static int is_elf(const char *path) {
  unsigned char magic[4];
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 0;
  ssize_t n = read(fd, magic, sizeof(magic));
  close(fd);
  return n == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' &&
         magic[3] == 'F';
}

/* execve(LINKER, [LINKER, path, argv[0], argv[1], ...], envp). The linker
 * treats argv[1] as the ELF to load and hands argv[2:] to the program, so the
 * original argv is preserved verbatim. */
static int exec_via_linker(const char *path, char *const argv[],
                           char *const envp[]) {
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
 * 0 when the reroute already ran (success never returns; failure keeps the
 * linker's errno unless it is ENOENT/EACCES, in which case we fall through so
 * the kernel gets a chance — e.g. a writable-executable file the vendor W^X
 * rejects might still be exec'd directly after the write bit was stripped). */
static int maybe_reroute(const char *path, char *const argv[],
                         char *const envp[]) {
  if (!path || strncmp(path, LINKER, LINKER_LEN) == 0) return 1;
  if (!is_elf(path)) return 1;
  if (exec_via_linker(path, argv, envp) == -1 && errno != ENOENT &&
      errno != EACCES) {
    return 0;
  }
  return 1;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
  if (maybe_reroute(path, argv, envp)) {
    return (int)syscall(SYS_execve, path, argv, envp);
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
