# GhidraStabs

A Ghidra extension that imports the STABS debug info emitted by gcc 2.95.3 (C and g++ 2.95 C++) from ELF binaries.

It imports:

- structs, unions, enums and typedefs (under `/STABS`)
- C++ classes: members, methods, base classes and nested classes
- function names, namespaces and signatures (gnu-v2 demangled)
- parameters, stack locals and register locals
- global and static data

It runs as the **STABS** analyzer in Auto Analysis and enables itself for ELF programs that have `.stab`/`.stabstr` sections.

## Build

Requires Java 21 and Ghidra 12.1.4.

```sh
export GHIDRA_INSTALL_DIR=/path/to/ghidra_12.1.4_PUBLIC
./gradlew buildExtension
```

The zip goes to `dist/`. Install it with **File → Install Extensions** and restart Ghidra.

## Test

```sh
./gradlew test
```

To also parse real binaries, point `stabs.testBinaries` at a directory of ELF files with STABS (`-Pstabs.testBinaries=<dir>`, the `STABS_TEST_BINARIES` environment variable, or `local.properties`). Every file in it that has `.stab`/`.stabstr` sections must parse without issues.

## Known limitations

- Vtable layouts and line numbers aren't imported yet.
- Classes that g++ passes by invisible reference are detected heuristically.
- Disable Ghidra 12.1.4's **GCC Exception Handlers** analyzer on relocated `.so` files: it mislocates `.eh_frame` entries in relocated images (see `docs/ghidra-eh-frame-image-base.md`).
