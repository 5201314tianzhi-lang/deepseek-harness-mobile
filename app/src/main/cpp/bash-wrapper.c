/*
 * bash-wrapper: the executable at usr/bin/bash that routes agent shell
 * commands into the proot Ubuntu container.
 *
 * Why an ELF instead of the historical shell script: the kernel exec of a
 * script inside app data is refused with EACCES on hardened devices (Android
 * 15+ / vendor W^X / SELinux), and the LD_PRELOAD exec-hook cannot help —
 * it only sees libc execve, while script exec is handled by the kernel
 * (binfmt_script). An ELF wrapper is rerouted by the hook through
 * /system/bin/linker64 (the same dlopen-style load that already runs the
 * engine's node), so the container chain never needs a kernel-level script
 * exec. Observed on a Huawei/EMUI device: the script wrapper failed with
 * EACCES for every shebang/mode combination, while the ELF path works.
 *
 * All dsh paths come from the environment (set by the app on the engine and
 * probe processes): when the hook loads this file via linker64,
 * /proc/self/exe points at the linker, so self-location is impossible.
 * Missing variables fail loudly (126) instead of silently exec'ing the
 * wrong thing.
 */
#define _GNU_SOURCE
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define ENV_FILES "DSH_FILES_DIR"
#define ENV_WORKSPACE "DSH_WORKSPACE"

static void fail(const char *msg, int code) {
  fprintf(stderr, "bash wrapper: %s\n", msg);
  exit(code);
}

int main(int argc, char **argv) {
  const char *files = getenv(ENV_FILES);
  if (!files || !*files) fail("missing " ENV_FILES, 126);
  const char *workspace = getenv(ENV_WORKSPACE);
  if (!workspace || !*workspace) fail("missing " ENV_WORKSPACE, 126);

  char proot[PATH_MAX + 64], rootfs[PATH_MAX + 64], resolv[PATH_MAX + 64],
    tmp[PATH_MAX + 64];
  snprintf(proot, sizeof(proot), "%s/proot/proot", files);
  snprintf(rootfs, sizeof(rootfs), "%s/rootfs", files);
  snprintf(resolv, sizeof(resolv), "%s/etc/resolv.conf", files);
  snprintf(tmp, sizeof(tmp), "%s/home/tmp", files);

  char guest[PATH_MAX + 64];
  snprintf(guest, sizeof(guest), "%s/bin/bash", rootfs);
  struct stat st;
  if (stat(guest, &st) != 0 || !S_ISREG(st.st_mode)) {
    fprintf(stderr, "Ubuntu container not installed\n");
    return 127;
  }

  /* proot's scratch space: writable app storage (glue rootfs, f2fs probe,
   * mkdtemp). The container-internal TMPDIR points at /tmp, which lives in
   * the (writable) rootfs. */
  mkdir(tmp, 0700);
  setenv("PROOT_TMP_DIR", tmp, 1);
  setenv("TMPDIR", "/tmp", 1);
  /* proot and its deps (libtalloc, libandroid-shmem) live in files/proot. */
  const char *old_ld = getenv("LD_LIBRARY_PATH");
  char ld[2 * PATH_MAX + 64];
  if (old_ld && *old_ld) {
    snprintf(ld, sizeof(ld), "%s/proot:%s", files, old_ld);
  } else {
    snprintf(ld, sizeof(ld), "%s/proot", files);
  }
  setenv("LD_LIBRARY_PATH", ld, 1);

  /* proot -0 -r <rootfs> -b /proc -b /dev -b /sys \
   *   -b <resolv>:/etc/resolv.conf -b <workspace>:/root/projects \
   *   -w /root/projects /bin/bash <user argv> */
  char resolv_bind[PATH_MAX + 64], ws_bind[PATH_MAX + 64];
  snprintf(resolv_bind, sizeof(resolv_bind), "%s:/etc/resolv.conf", resolv);
  snprintf(ws_bind, sizeof(ws_bind), "%s:/root/projects", workspace);
  char **newargv = malloc(((size_t)(argc > 0 ? argc : 1) + 17) * sizeof(char *));
  if (!newargv) fail("out of memory", 126);
  int i = 0;
  newargv[i++] = proot;
  newargv[i++] = "-0";
  newargv[i++] = "-r";
  newargv[i++] = rootfs;
  newargv[i++] = "-b";
  newargv[i++] = "/proc";
  newargv[i++] = "-b";
  newargv[i++] = "/dev";
  newargv[i++] = "-b";
  newargv[i++] = "/sys";
  newargv[i++] = "-b";
  newargv[i++] = resolv_bind;
  newargv[i++] = "-b";
  newargv[i++] = ws_bind;
  newargv[i++] = "-w";
  newargv[i++] = "/root/projects";
  newargv[i++] = "/bin/bash";
  for (int j = 1; j < argc; j++) newargv[i++] = argv[j];
  newargv[i] = NULL;

  /* The exec-hook (LD_PRELOAD, inherited through the whole tree) reroutes
   * this execve to /system/bin/linker64 — the dlopen-style load that Android
   * permits for app data on every version and vendor. */
  execv(proot, newargv);
  fail("proot exec failed", 126);
}