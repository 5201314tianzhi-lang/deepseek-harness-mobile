/*
 * bash-wrapper host test: exercises the proot wrapper ELF on a Linux box
 * with a fake proot. Covers argv construction, env injection (LD_LIBRARY_PATH
 * prepend, PROOT_TMP_DIR, TMPDIR) and the error paths (missing DSH_FILES_DIR
 * -> 126, missing rootfs bash -> 127).
 */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

/* Fake proot: echoes its argv and the dsh env the wrapper must inject. */
static void write_fake_proot(void) {
  FILE *fp = fopen("files/proot/proot", "w");
  assert(fp);
  fprintf(fp, "#!/bin/sh\nfor a in \"$@\"; do echo \"ARG:$a\"; done\n");
  fprintf(fp, "echo \"LD:$LD_LIBRARY_PATH\"\n");
  fprintf(fp, "echo \"PTMP:$PROOT_TMP_DIR\"\n");
  fprintf(fp, "echo \"TMP:$TMPDIR\"\n");
  fclose(fp);
  assert(chmod("files/proot/proot", 0755) == 0);
}

static void mkdir_p(const char *path) {
  char buf[512];
  snprintf(buf, sizeof(buf), "mkdir -p %s", path);
  assert(system(buf) == 0);
}

/* Run the wrapper as a child, capturing stdout+stderr and the exit code. */
static void run(const char *const argv[], char *out, size_t outsz, int *code) {
  int fds[2];
  assert(pipe(fds) == 0);
  pid_t pid = fork();
  assert(pid >= 0);
  if (pid == 0) {
    assert(dup2(fds[1], 1) >= 0);
    assert(dup2(fds[1], 2) >= 0);
    close(fds[0]);
    execv("./bash-wrapper", (char *const *)argv);
    _exit(127);
  }
  close(fds[1]);
  /* Drain until EOF: a single read() may return early with partial output,
   * and closing the read end while the child is still writing SIGPIPEs it. */
  size_t got = 0;
  while (got + 1 < outsz) {
    ssize_t n = read(fds[0], out + got, outsz - 1 - got);
    if (n <= 0) break;
    got += (size_t)n;
  }
  close(fds[0]);
  int status = 0;
  waitpid(pid, &status, 0);
  out[got] = 0;
  *code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static void test_routing(void) {
  char out[8192];
  int code = -1;
  const char *const argv[] = {"bash", "-c", "echo hi", NULL};
  run(argv, out, sizeof(out), &code);
  assert(code == 0);
  assert(strstr(out, "ARG:-0"));
  assert(strstr(out, "ARG:-r"));
  assert(strstr(out, "ARG:files/rootfs"));
  assert(strstr(out, "ARG:-b"));
  assert(strstr(out, "ARG:/proc"));
  assert(strstr(out, "ARG:/dev"));
  assert(strstr(out, "ARG:/sys"));
  assert(strstr(out, "ARG:files/etc/resolv.conf:/etc/resolv.conf"));
  assert(strstr(out, "ARG:files/dshdata/workspace:/root/projects"));
  assert(strstr(out, "ARG:-w"));
  assert(strstr(out, "ARG:/root/projects"));
  assert(strstr(out, "ARG:/bin/bash"));
  assert(strstr(out, "ARG:-c"));
  assert(strstr(out, "ARG:echo hi"));
  /* Env injection: LD_LIBRARY_PATH prepended with the proot dir, proot
   * scratch in writable app storage, container TMPDIR at /tmp. */
  assert(strstr(out, "LD:files/proot:/pre"));
  assert(strstr(out, "PTMP:files/home/tmp"));
  assert(strstr(out, "TMP:/tmp"));
  printf("wrapper-routing-ok\n");
}

static void test_missing_files_env(void) {
  unsetenv("DSH_FILES_DIR");
  char out[1024];
  int code = -1;
  const char *const argv[] = {"bash", NULL};
  run(argv, out, sizeof(out), &code);
  assert(code == 126);
  assert(strstr(out, "missing DSH_FILES_DIR"));
  printf("wrapper-missing-env-ok\n");
}

static void test_missing_rootfs(void) {
  assert(remove("files/rootfs/bin/bash") == 0);
  setenv("DSH_FILES_DIR", "files", 1); // restored: the env test unset it
  char out[1024];
  int code = -1;
  const char *const argv[] = {"bash", NULL};
  run(argv, out, sizeof(out), &code);
  assert(code == 127);
  assert(strstr(out, "Ubuntu container not installed"));
  printf("wrapper-no-rootfs-ok\n");
}

int main(void) {
  mkdir_p("files/proot");
  mkdir_p("files/rootfs/bin");
  mkdir_p("files/etc");
  mkdir_p("files/dshdata/workspace");
  mkdir_p("files/home");
  write_fake_proot();
  FILE *fp = fopen("files/rootfs/bin/bash", "w");
  assert(fp);
  fclose(fp);
  fp = fopen("files/etc/resolv.conf", "w");
  assert(fp);
  fclose(fp);

  setenv("DSH_FILES_DIR", "files", 1);
  setenv("DSH_WORKSPACE", "files/dshdata/workspace", 1);
  setenv("LD_LIBRARY_PATH", "/pre", 1);
  test_routing();
  test_missing_files_env();
  test_missing_rootfs();
  printf("ALL WRAPPER TESTS PASSED\n");
  return 0;
}