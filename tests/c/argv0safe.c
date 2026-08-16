/* argv[0]-safe helper for the NULL-argv test: the Linux kernel accepts a
 * NULL argv as an empty one, so the exec'd program must not dereference
 * argv[0] (coreutils' echo does — it crashes with SIGSEGV, which would be
 * indistinguishable from a hook-internal crash). This program never touches
 * argv, so a successful exec + clean exit 0 proves the hook survived NULL
 * argv and fell back to the native syscall correctly. */
int main(int argc, char **argv) {
  (void)argc;
  (void)argv;
  return 0;
}
