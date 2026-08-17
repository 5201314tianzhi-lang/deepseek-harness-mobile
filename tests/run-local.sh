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
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
int main(int argc, char **argv) {
  /* Invoked as: fake-linker <elf-path> <orig-argv[0]> <orig-argv[1]> ...
   * The reroute must preserve the original argv verbatim. */
  if (argc >= 4 && strcmp(argv[2], "echo") == 0 && strcmp(argv[3], "hello") == 0) {
    printf("rerouted-ok\n");
    return 0;
  }
  /* bash-fix-test: rewritten PTY spawn — argv must survive and the DSH_*
   * variables scrubbed by dsh must be re-injected into our environment. */
  if (argc >= 6 && strcmp(argv[2], "/bin/bash") == 0 &&
      strcmp(argv[3], "--noprofile") == 0 && strcmp(argv[5], "-i") == 0) {
    if (getenv("DSH_FILES_DIR") && getenv("DSH_WORKSPACE")) {
      printf("rewrite-ok\n");
      return 0;
    }
    return 2;
  }
  /* bash-fix-test: one-shot wrapper exec — env must be re-injected. */
  if (argc >= 4 && strcmp(argv[2], "bash") == 0 && strcmp(argv[3], "-c") == 0) {
    if (getenv("DSH_FILES_DIR") && getenv("DSH_WORKSPACE")) {
      printf("env-ok\n");
      return 0;
    }
    return 3;
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
  # bash rewrite + DSH_* env re-injection (the wrapper ELF stands in for
  # usr/bin/bash at a fake DSH_FILES_DIR; DSH_* vars are in the runner env
  # exactly as they are in the engine process).
  gcc -O2 -Wall -Wextra -o bash-fix-test bash-fix-test.c -Wno-unused-parameter -Wno-nonnull
  mkdir -p bashfixtest/usr/bin
  cp fake-linker bashfixtest/usr/bin/bash
  DSH_FILES_DIR="$PWD/bashfixtest" DSH_WORKSPACE="$PWD/bashfixtest/workspace" \
    LD_PRELOAD="$PWD/exec-hook-reroute.so" ./bash-fix-test
  rm -rf bashfixtest
  rm -f fake-linker.c fake-linker reroute-check.c reroute-check exec-hook-reroute.so exec-hook.so exec-hook-test argv0safe bash-fix-test
  echo "== C: bash wrapper (proot routing ELF) =="
  # -Wno-format-truncation: fortify's theoretical analysis of the join
  # buffers (2x PATH_MAX vs app paths ~50 chars); truncation cannot happen
  # and would fail loudly if it did.
  gcc -O2 -Wall -Wextra -Wno-format-truncation -o bash-wrapper ../../app/src/main/cpp/bash-wrapper.c
  gcc -O2 -Wall -Wextra -o bash-wrapper-test bash-wrapper-test.c
  ./bash-wrapper-test
  rm -rf files bash-wrapper bash-wrapper-test
else
  echo "   (gcc not found; skipping C tests)"
fi

echo "ALL LOCAL TESTS PASSED"
