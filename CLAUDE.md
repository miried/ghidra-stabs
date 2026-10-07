# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Goal

Import the STABS debug info that gcc 2.95.3 emitted (C and g++ 2.95 C++) into Ghidra: structs, unions, enums, typedefs, C++ classes (members, methods, base classes, vtables), and function signatures.

## Commands

Set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` first. There's no system Java.

- `./gradlew test`: unit tests plus a parse of the real binaries (zero issues, zero undefined type refs, every member function matched to a class physname).
- `./gradlew buildExtension`: builds the installable zip in `dist/`.
- `./gradlew importStabs [-Pfile=<elf>] [-Pargs="<type or function names>"]`: imports the ELF into a throwaway headless Ghidra project, runs `StabsAnalyzer` only, and prints the summary, `.conflict` types, custom-storage functions and the named types/functions. Use it to verify the Ghidra side without reinstalling the extension.
- `./gradlew dumpStabs [-Pfile=<elf>] [-Pargs="<mode> [filter]"]`: runs the parser outside Ghidra. Modes are `summary`, `check`, `types`, `functions`, `globals`, `issues` and `raw`. `file` defaults to `cgame.so`.

## Design

- It's a Ghidra extension whose entry point is `stabs.analysis.StabsAnalyzer`, so it appears in the Auto Analysis options, the same way `DWARFAnalyzer` does. The analyzer only enables itself for ELF programs that have `.stab`/`.stabstr`.
- Data flows through four layers:
  - `stabs.format` decodes raw entries.
  - `stabs.parse` turns them into the model. `TypeParser` handles type strings and `StabsParser` the stream state.
  - `stabs.model` is plain Java with no Ghidra dependency.
  - The Ghidra-specific code lives only in `stabs.analysis`: `StabsTypeImporter` (data types under `/STABS`) and `StabsImporter` (function names/namespaces/signatures, global and static data).
- Keep `model`/`parse` free of Ghidra APIs so they stay testable on the raw ELF through `stabs.tools.ElfSections`.
- The parser follows binutils `stabs.c`. Where it deliberately differs, the code comment says why (e.g. how method physnames are decided).

## Gotchas

- Compile against Ghidra **12.1.4** (`/opt/homebrew/Cellar/ghidra/12.1.4/libexec`), not `~/code/ghidra` master. Master has APIs that 12.1.4 lacks (e.g. `ElfLoader.isElf`). Check with `javap` against the jars in the install.
- Stab values are link-time addresses: base 0 for the `.so` files. Add `StabsAnalyzer.imageBaseDelta`. Ignore `.rel.stab`, because the stored values are already final. Read the section bytes from the original `FileBytes`.
- N_SLINE/N_LBRAC/N_RBRAC values are relative to the enclosing N_FUN.
- The linker merged all units under a single N_UNDF header, and its 16-bit n_desc count overflows, so never rely on it.
- The same header can be N_BINCL'd with different hashes in different units. That's why one class (e.g. `Listener`) shows up as several identical `StructType`s, so deduplicate when creating Ghidra types.
- g++ 2.95 encodes operators as mangled method names (`__as` is `operator=`), the vtable pointer as a `.vf` field, and anonymous types as `._N`. Function names in N_FUN are gnu-v2 mangled.

## Reference sources (read-only, never edit)

- `~/code/ghidra`: Ghidra source, for patterns such as the DWARF importer (`ghidra/app/util/bin/format/dwarf`).
- `~/code/gcc-2.95.3`: `gcc/dbxout.c` is the ground truth for what gets emitted.
- `~/code/binutils`: `binutils/stabs.c` is the reference parser. With the user's HEAD commit, `objdump --debugging` output is machine-parseable, so it works as a test oracle.
  - `~/code/binutils/build-objdump/binutils/objdump` was configured with `--target=aarch64-apple-darwin`, so it only reads Mach-O and can't open these binaries.
  - An objdump that reads elf32-i386 needs an out-of-tree configure with `--host=aarch64-apple-darwin --target=i686-pc-linux-gnu` (otherwise the same flags as `build-objdump/config.log`).
  - Then run `make all-binutils MAKEINFO=true CFLAGS="-O2 -std=gnu11 -Wno-error=incompatible-function-pointer-types"`. The flag is needed because the user's commit left `prdbg.c`'s `tg_start_compilation_unit` with the old signature, which only `--debugging-tags` uses.

## Test binaries

`~/mohaa-lnxclient-beta1/` holds i386 ELF files with STABS: `cgame.so` (the smallest), `fgame.so` and `mohaa_lnx`.

## Ghidra

A running Ghidra exposes an MCP server on `localhost:8080`. Installing or reloading the extension and restarting Ghidra is done by the user, so ask them.

## Status and open issues

Headless results (`./gradlew importStabs`):

- `cgame.so` is clean: 0 warnings, 3 custom-storage functions (all correct: the STABS omit an unnamed first parameter), and 14 `.conflict` types. The conflicts are all genuine, i.e. different definitions sharing a name: nested `Entry`/`block_s`, the two `EventQueueNode` layouts, C `exception` vs C++ `exception`, `wchar_t`.
- `fgame.so` completes, but with about 281 type warnings `Invalid structure: Entry...` and about 700 `.conflict` types. When resolving fails, the type falls back to undefined bytes, so e.g. `trace_t`, `gentity_s` and `ClassDef` are missing or degraded there.
- `mohaa_lnx` has 13 such warnings, all in `con_set`/`con_map` `Entry` classes.

Open bugs, most important first:

1. **Ambiguous nested class names cause cyclic types.** g++ 2.95 names nested classes without their enclosing class (`con_map<K,V>::Entry` is just `Entry`), and refers to incomplete ones with `xsEntry:`. `StabsParser.resolveCrossRefs` picks a candidate by tag, unit and language, so it can choose the wrong `Entry`. Ghidra then rejects the result with `DataTypeDependencyException: Data type Entry.conflictN has Entry.conflictN within it`. `StabsTypeImporter` has a guard for direct self-containment, `containsFilling`, plus a size-mismatch check, but longer cycles still get through. The likely fix is in the parser: when resolving an xref used as a by-value member or base, choose the candidate whose size matches the member's bit size. Also prefer a candidate from the same N_BINCL type table. The template args in the enclosing type's name (e.g. `con_set<K,con_map<K,V>::Entry>`) are another hint. `StabsTypeImporter.cyclePath` was meant to print the chain but printed nothing on fgame. Check why.
2. **Struct dedup key** (`StabsTypeImporter.layoutKey`, 3 levels deep, looks through typedefs) is a heuristic. If it merges two different classes, that can also produce a cycle (see 1).
3. **Classes passed by invisible reference.** g++ passes classes needing a copy ctor by hidden pointer, while the STABS give the class type. `StabsImporter.findClassesPassedByReference` infers this from 4-byte gaps between parameter offsets. It cannot detect a class that only ever appears as the last parameter.
4. Not imported yet: local variables and register/frame locals, vtable structures and virtual method slots, line numbers, and static member functions/`static` methods placed in class namespaces (only matched physnames are). Template functions (`__H` mangling) keep their mangled names. Conversion operators are named `operator_cast`.
