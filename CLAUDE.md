# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Goal

Import the STABS debug info that gcc 2.95.3 emitted (C and g++ 2.95 C++) into Ghidra: structs, unions, enums, typedefs, C++ classes (members, methods, base classes, vtables), and function signatures.

## Commands

Set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` first. There's no system Java.

- `./gradlew test`: unit tests plus a parse of the real binaries (zero issues, zero undefined type refs, every member function matched to a class physname).
- `./gradlew buildExtension`: builds the installable zip in `dist/`.
- `./gradlew importStabs [-Pfile=<elf>] [-Pargs="<type or function names>"]`: imports the ELF into a throwaway headless Ghidra project, runs `StabsAnalyzer` only, and prints the summary, `.conflict` types, custom-storage functions and the named types/functions. Use it to verify the Ghidra side without reinstalling the extension.
- `./gradlew fullAnalysis [-Pfile=<elf>] [-Pargs="stabs|nostabs [function names]"]`: runs full auto analysis on a throwaway import. It lists the noreturn functions, decompiles the named functions, and counts how many imported register locals the decompiler actually uses.
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

- `cgame.so`: 0 warnings, 3 custom-storage functions (all correct: the STABS omit an unnamed first parameter), 6 `.conflict` types, all genuine: the two `EventQueueNode` layouts, C `exception` vs C++ `exception`, and `wchar_t`/`__wchar_t`, which C units define as 4 bytes and C++ units (short wchar) as 2 bytes.
- `fgame.so`: 0 warnings, 5 `.conflict` types. `mohaa_lnx`: 0 warnings, about 40 `.conflict` types, mostly libjpeg's per-file `my_*` structs and Xlib's `_XPrivDisplay`. That one hasn't been checked yet.

Nested classes: g++ 2.95 names them without their enclosing class (`con_map<K,V>::Entry` is just `Entry`), but their methods' physnames carry the qualified mangling (`__as__Q2t7con_map2Z..5Entry...`). `StabsParser.qualifyNestedClasses` renames them to `con_map<K,V>::Entry`. `resolveCrossRefs` then looks `xsEntry:` up from the scope of the struct that contains the reference (`CrossRefType.owner`), the way C++ does. In Ghidra they go into a category named after the enclosing class (`/STABS/con_map<...>/Entry`) and nested class namespaces. A nested class without any methods would stay unqualified.

Locals: a block's variables come before its N_LBRAC, and `Function.Local` keeps the block range. Stack locals are added for the whole function. A local that conflicts with an earlier one is skipped. Most skips are inlined copies (`this`, `a`, `i`...) reusing slots, plus locals in `%st(n)`. A function emitted by several units (templates, inlines) only gets its locals once.

Register locals: the decompiler links a register local only through a HighVariable's name representative (its earliest def, exact address). It often merges a variable with copies in other registers, or moves it into unique space. So `firstUseOffset` = block start (what the DWARF importer does) attaches about 27% of the time and leaves the rest as dead declarations. Instead, `StabsAnalyzer` hands them to `StabsRegisterLocalsAnalyzer` (after function analysis). That analyzer decompiles the functions and picks the HighVariable with the most defs in the block, counting outputs in the register and unique outputs of instructions that write it. It commits that variable via `HighFunctionDBUtil.updateDBVariable`, like a rename in the decompiler. cgame: 714 committed with GCC Exception Handlers off (545 with it on), nearly all used. The rest are skipped rather than guessed, and so are locals in zero-length blocks (inlined code gcc optimized away).

Noreturn: Ghidra 12.1.4's **GCC Exception Handlers** analyzer adds the image base twice to the relocated `.eh_frame` `pc_begin` values. It creates ~1000 bogus functions mid-function, and "Non-Returning Functions - Discovered" then wrongly marks 22 functions (`rand`, `strcpy`, `AnglesToAxis`...) noreturn. Not the importer. Workaround: disable GCC Exception Handlers. That leaves 2 noreturn (`abort`) and raises cgame's register locals from 545 to 714. Not fixed in the installed Ghidra; master looks fixed. Details: `docs/ghidra-eh-frame-image-base.md`.

Skip reasons: both STABS summaries list skipped locals per reason. Stack: `copy` (the same inlined variable again), `aliases parameter` (an inlined function's parameter in the caller's slot), `slot shared` (Ghidra allows one variable per stack slot). Register: `empty block` (gcc optimized the block away), `no write`, `no candidate`, `duplicate`.

Open issues:

1. **Classes passed by invisible reference.** g++ passes classes needing a copy ctor by hidden pointer, while the STABS give the class type. `StabsImporter.findClassesPassedByReference` infers this from 4-byte gaps between parameter offsets. It cannot detect a class that only ever appears as the last parameter.
2. **Struct dedup key** (`StabsTypeImporter.layoutKey`, 3 levels deep, looks through typedefs) is a heuristic. If it merges two different classes, that can produce a cyclic type (Ghidra `DataTypeDependencyException`). `StabsTypeImporter.containsFilling` and the size-mismatch check guard against that.
3. Not imported yet: vtable structures and virtual method slots, line numbers, and static member functions/`static` methods placed in class namespaces (only matched physnames are). The runtime's `__tf<class>` type_info functions and `_GLOBAL_.I/D` initializers keep their mangled names. Template functions (`__H` mangling, not in cgame) keep their mangled names. Conversion operators are named `operator_cast`.
