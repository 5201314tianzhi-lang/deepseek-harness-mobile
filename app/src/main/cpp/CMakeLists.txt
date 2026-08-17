cmake_minimum_required(VERSION 3.22.1)
project(exec-hook C)

add_library(exec-hook SHARED exec-hook.c)

# usr/bin/bash proot wrapper: a plain executable (not a script), so its exec
# is rerouted through linker64 by the exec-hook instead of the kernel's
# script path (which hardened devices refuse with EACCES). Packaged at
# lib/<abi>/bash-wrapper; extracted to usr/bin/bash at runtime.
add_executable(bash-wrapper bash-wrapper.c)
