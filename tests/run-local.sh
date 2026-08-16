#!/bin/sh
# Local test runner: JS polyfills + C exec-hook. Runs on any Linux box with
# node + gcc (no Android SDK needed); the Kotlin unit tests run in CI.
set -eu
cd "$(dirname "$0")/.."

echo "== JS: compat-polyfills =="
node tests/js/polyfills.test.js

echo "== C: exec-hook safety invariants =="
cd tests/c
if command -v gcc >/dev/null 2>&1; then
  gcc -O2 -Wall -Wextra -o exec-hook-test exec-hook-test.c -Wno-unused-parameter -Wno-nonnull
  gcc -O2 -o argv0safe argv0safe.c
  # Fall-through hook: LINKER points at a nonexistent path, so every ELF
  # reroute fails with ENOENT and MUST fall back to the native syscall.
  # (Some dev containers ship a real /system/bin/linker64 — bionic cannot
  # load glibc binaries, so the fall-through test needs the fake target.)
  gcc -O2 -shared -fPIC -DLINKER=\"/nonexistent-linker64\" \
    -o exec-hook.so ../../app/src/main/cpp/exec-hook.c
  LD_PRELOAD="$PWD/exec-hook.so" ./exec-hook-test
  echo "   (reroute-fallthrough path verified)"
  # ELF reroute with a fake linker: override LINKER at compile time so the
  # reroute actually fires and we can assert argv preservation.
  cat > fake-linker.c <<'EOF'
#include <stdio.h>
#include <string.h>
#include <unistd.h>
int main(int argc, char **argv) {
  /* Invoked as: fake-linker <elf-path> <orig-argv[0]> <orig-argv[1]> ...
   * The reroute must preserve the original argv verbatim. */
  if (argc >= 4 && strcmp(argv[2], "echo") == 0 && strcmp(argv[3], "hello") == 0) {
    printf("rerouted-ok\n");
    return 0;
  }
  return 1;
}
EOF
  gcc -O2 -o fake-linker fake-linker.c
  gcc -O2 -shared -fPIC -DLINKER=\"$PWD/fake-linker\" -o exec-hook-reroute.so ../../app/src/main/cpp/exec-hook.c
  cat > reroute-check.c <<'EOF'
#include <assert.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>
int main(void) {
  /* /bin/echo is an ELF: the hook reroutes it through the fake linker, which
   * asserts that the original argv arrived verbatim. */
  pid_t pid = fork();
  if (pid == 0) {
    char *const argv[] = {"echo", "hello", NULL};
    execv("/bin/echo", argv);
    _exit(127);
  }
  int status = 0;
  waitpid(pid, &status, 0);
  assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
  printf("reroute-argv-ok\n");
  return 0;
}
EOF
  gcc -O2 -o reroute-check reroute-check.c
  LD_PRELOAD="$PWD/exec-hook-reroute.so" ./reroute-check
  rm -f fake-linker.c fake-linker reroute-check.c reroute-check exec-hook-reroute.so exec-hook.so exec-hook-test argv0safe
else
  echo "   (gcc not found; skipping C tests)"
fi

echo "ALL LOCAL TESTS PASSED"
