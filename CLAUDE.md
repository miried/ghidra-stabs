# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. Machine-specific paths, test binaries and per-binary results are in `CLAUDE.local.md` (not committed).

## Goal

Import the STABS debug info that gcc 2.95.3 emitted (C and g++ 2.95 C++) into Ghidra: structs, unions, enums, typedefs, C++ classes (members, methods, base classes, vtables), and function signatures.

## Commands

Requires Java 21 (`JAVA_HOME`) and Ghidra 12.1.4. Build settings come from the environment, `-P` properties or an untracked `local.properties` (see the top of `build.gradle`): `GHIDRA_INSTALL_DIR`, `stabs.file` (default ELF for the stabs tasks) and `stabs.testBinaries` (directory of ELFs for `RealBinariesTest`).

- `./gradlew test`: unit tests plus a parse of every STABS ELF in `stabs.testBinaries`, if set (zero issues, zero undefined type refs, every member function matched to a class physname).
- `./gradlew buildExtension`: builds the installable zip in `dist/`.
- `./gradlew importStabs [-Pfile=<elf>] [-Pargs="<type or function names>"]`: imports the ELF into a throwaway headless Ghidra project, runs `StabsAnalyzer` only, and prints the summary, `.conflict` types, custom-storage functions and the named types/functions. Use it to verify the Ghidra side without reinstalling the extension.
- `./gradlew fullAnalysis [-Pfile=<elf>] [-Pargs="stabs|nostabs [function names]"]`: runs full auto analysis on a throwaway import. It lists the noreturn functions, decompiles the named functions, and counts how many imported register locals the decompiler actually uses.
- `./gradlew dumpStabs [-Pfile=<elf>] [-Pargs="<mode> [filter]"]`: runs the parser outside Ghidra. Modes are `summary`, `check`, `types`, `functions`, `globals`, `issues` and `raw`.

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

- Compile against Ghidra **12.1.4**, not Ghidra master. Master has APIs that 12.1.4 lacks (e.g. `ElfLoader.isElf`). Check with `javap` against the jars in the install.
- Stab values are link-time addresses: base 0 for `.so` files. Add `StabsAnalyzer.imageBaseDelta`. Ignore `.rel.stab`, because the stored values are already final. Read the section bytes from the original `FileBytes`.
- N_SLINE/N_LBRAC/N_RBRAC values are relative to the enclosing N_FUN.
- The linker can merge all units under a single N_UNDF header, and its 16-bit n_desc count overflows, so never rely on it.
- The same header can be N_BINCL'd with different hashes in different units. That's why one class shows up as several identical `StructType`s, so deduplicate when creating Ghidra types.
- g++ 2.95 encodes operators as mangled method names (`__as` is `operator=`), the vtable pointer as a `.vf` field, and anonymous types as `._N`. Function names in N_FUN are gnu-v2 mangled.

## Reference sources

- Ghidra source, for patterns such as the DWARF importer (`ghidra/app/util/bin/format/dwarf`).
- gcc 2.95.3: `gcc/dbxout.c` is the ground truth for what gets emitted.
- binutils: `binutils/stabs.c` is the reference parser; `objdump --debugging` works as a test oracle.

## Implementation notes

Nested classes: g++ 2.95 names them without their enclosing class (`Outer<K,V>::Entry` is just `Entry`), but their methods' physnames carry the qualified mangling (`__as__Q2t5Outer2Z..5Entry...`). `StabsParser.qualifyNestedClasses` renames them to `Outer<K,V>::Entry`. `resolveCrossRefs` then looks `xsEntry:` up from the scope of the struct that contains the reference (`CrossRefType.owner`), the way C++ does. In Ghidra they go into a category named after the enclosing class (`/STABS/Outer<...>/Entry`) and nested class namespaces. A nested class without any methods would stay unqualified.

Locals: a block's variables come before its N_LBRAC, and `Function.Local` keeps the block range. Stack locals are added for the whole function. A local that conflicts with an earlier one is skipped. Most skips are inlined copies (`this`, `a`, `i`...) reusing slots, plus locals in `%st(n)`. A function emitted by several units (templates, inlines) only gets its locals once.

Register locals: the decompiler links a register local only through a HighVariable's name representative (its earliest def, exact address). It often merges a variable with copies in other registers, or moves it into unique space. So `firstUseOffset` = block start (what the DWARF importer does) attaches only about 27% of the time and leaves the rest as dead declarations. Instead, `StabsAnalyzer` hands them to `StabsRegisterLocalsAnalyzer` (after function analysis). That analyzer decompiles the functions and picks the HighVariable with the most defs in the block, counting outputs in the register and unique outputs of instructions that write it. It commits that variable via `HighFunctionDBUtil.updateDBVariable`, like a rename in the decompiler. The rest are skipped rather than guessed, and so are locals in zero-length blocks (inlined code gcc optimized away).

Noreturn: Ghidra 12.1.4's **GCC Exception Handlers** analyzer adds the image base twice to the relocated `.eh_frame` `pc_begin` values. It creates many bogus functions mid-function, and "Non-Returning Functions - Discovered" then wrongly marks ordinary functions (`rand`, `strcpy`...) noreturn. Not the importer. Workaround: disable GCC Exception Handlers, which also raises the number of register locals committed. Ghidra master looks fixed. Details: `docs/ghidra-eh-frame-image-base.md`.

Skip reasons: both STABS summaries list skipped locals per reason. Stack: `copy` (the same inlined variable again), `aliases parameter` (an inlined function's parameter in the caller's slot), `slot shared` (Ghidra allows one variable per stack slot). Register: `empty block` (gcc optimized the block away), `no write`, `no candidate`, `duplicate`.

## Open issues

1. **Classes passed by invisible reference.** g++ passes classes needing a copy ctor by hidden pointer, while the STABS give the class type. `StabsImporter.findClassesPassedByReference` infers this from 4-byte gaps between parameter offsets. It cannot detect a class that only ever appears as the last parameter.
2. **Struct dedup key** (`StabsTypeImporter.layoutKey`, 3 levels deep, looks through typedefs) is a heuristic. If it merges two different classes, that can produce a cyclic type (Ghidra `DataTypeDependencyException`). `StabsTypeImporter.containsFilling` and the size-mismatch check guard against that.
3. Not imported yet: vtable structures and virtual method slots, line numbers, and static member functions/`static` methods placed in class namespaces (only matched physnames are). The runtime's `__tf<class>` type_info functions and `_GLOBAL_.I/D` initializers keep their mangled names. Template functions (`__H` mangling) keep their mangled names. Conversion operators are named `operator_cast`.
