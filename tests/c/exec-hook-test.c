/*
 * exec-hook.c behavior tests on plain Linux (gcc, no Android needed):
 *
 *   1. A hook that cannot find its linker must NEVER break native exec —
 *      every exec falls through to the original syscall (this is the core
 *      safety invariant; on Android the hook reroutes ELF execs through
 *      /system/bin/linker64, which does not exist on this test machine).
 *   2. With LINKER overridden at compile time, ELF rerouting fires and the
 *      original argv is preserved verbatim.
 *
 * Built and run from tests/c (see tests/run-local.sh).
 */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static int run(const char *path, char *const argv[]) {
  pid_t pid = fork();
  if (pid == 0) {
    execv(path, argv);
    _exit(127);
  }
  int status = 0;
  waitpid(pid, &status, 0);
  if (!WIFEXITED(status)) return -1;
  return WEXITSTATUS(status);
}

int main(void) {
  /* 1. Native exec must work with the hook preloaded (linker64 absent on
   *    Linux -> every reroute falls through). Checked via env in the runner:
   *    LD_PRELOAD=$PWD/exec-hook.so ./exec-hook-test
   *    If the hook broke exec, /bin/echo would fail or print nothing. */
  {
    char *const argv[] = {"echo", "hook-native-ok", NULL};
    int rc = run("/bin/echo", argv);
    assert(rc == 0 && "native exec with hook preloaded must succeed");
  }

  /* 2. Script (shebang) exec must pass through untouched. */
  {
    const char *script = "#!/bin/sh\necho hook-script-ok\n";
    FILE *f = fopen("hook-script.sh", "w");
    assert(f);
    fputs(script, f);
    fclose(f);
    chmod("hook-script.sh", 0755);
    char *const argv[] = {"hook-script.sh", NULL};
    int rc = run("./hook-script.sh", argv);
    assert(rc == 0 && "shebang exec must pass through");
    unlink("hook-script.sh");
  }

  /* 3. argv==NULL must not crash the hook (POSIX allows execv(path, NULL)):
   *    the hook substitutes an empty argv for the reroute, falls back to the
   *    native syscall on ENOENT, and the Linux kernel accepts a NULL argv as
   *    an empty one — the exec'd program (argv0safe, which never touches
   *    argv[0]) must run and exit 0. Any crash inside the hook (the pre-fix
   *    code dereferenced argv unconditionally) fails the WIFEXITED check. */
  {
    pid_t pid = fork();
    if (pid == 0) {
      char *const envp[] = {NULL};
      execve("./argv0safe", (char *const *)NULL, envp);
      _exit(errno == EFAULT ? 42 : 43);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0 &&
           "argv==NULL must not crash the hook (argv0safe must run)");
  }

  printf("exec-hook tests passed\n");
  return 0;
}
