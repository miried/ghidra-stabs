# Ghidra 12.1.4: GCC Exception Handlers applies the image base twice

Ghidra 12.1.4 has this bug. The workaround is to disable **GCC Exception Handlers** in the
analysis options before analyzing relocated gcc 2.95 `.so` files.

## Symptom

In an affected `.so`, "Non-Returning Functions - Discovered" marks ordinary functions noreturn
(`rand`, `strcpy`, `sprintf`, ...). The decompiler then cuts off every caller after
such a call, and code after it isn't part of the function. That also costs STABS register
locals: their blocks are no longer in the function body (`no write` skips).

The marks are the same with and without the STABS analyzer.

## Cause

`.eh_frame` FDE `pc_begin` fields are `DW_EH_PE_absptr` and each one has an `R_386_RELATIVE`
relocation in `.rel.eh_frame`. The `.so` is linked at 0 and Ghidra loads it
at 0x10000, so the ELF loader writes `0x10000 + addend` into memory, which is correct.

One FDE shows what happens next:

| | value |
|---|---|
| file bytes (REL addend) | 0x88b64 |
| memory after the loader's relocation | 0x98b64, a function start (correct) |
| reference created by GCC Exception Handlers | 0xa8b64, inside an unrelated function |

`ghidra/app/plugin/exceptionhandlers/gcc/AbstractDwarfEHDecoder.java` in 12.1.4
(`Ghidra/Features/Base/lib/Base-src.zip`), `resolveRelativeOffset`:

```java
case DW_EH_PE_absptr:
    // adjust abs ptr for any changes to imagebase during import
    val = context.getImageBaseAdjustment() + val;
```

`getImageBaseAdjustment()` is `imageBase - ElfLoader.getElfOriginalImageBase()` = 0x10000. It is
added to the value read from memory, which the relocation has already moved.

The analyzer creates a function at every such address. In one test binary that was about 1000 bogus
functions, most
of them starting mid-instruction or mid-function. FindNoReturnFunctionsAnalyzer then sees
"function defined after call" / "function defined in instruction after call" behind real
calls and takes that as evidence that the callee doesn't return.

## Measured (headless, `./gradlew fullAnalysis`)

| test `.so`, full auto analysis | GCC Exception Handlers on | off |
|---|---|---|
| functions | 2480 | 1482 |
| functions marked noreturn | 22 | 2 (both `abort`) |
| STABS register locals applied | 545 | 714 |

The second run is reproducible with a file listing the analyzer name:

```
echo "GCC Exception Handlers" > /tmp/dis.txt
JAVA_TOOL_OPTIONS="-Dstabs.disable=/tmp/dis.txt -Dstabs.noReturnReasons=true" \
  ./gradlew fullAnalysis -Pargs=nostabs
```

`stabs.noReturnReasons` prints, for each callee, which of the noreturn analyzer's checks fired
at which call (`NoReturnReasons` in `src/test`).

## Upstream

Ghidra master (checked 2026-10-08) moved the adjustment into `decodeAddress`.
It now only applies it when there is no relocation at the decoded field:

```java
if (appMode == DwarfEHDataApplicationMode.DW_EH_PE_absptr &&
    prog.getRelocationTable().getRelocations(context.getAddress()).isEmpty()) {
    offset += getImageBaseAdjustment(prog);
}
```

`resolveRelativeOffset` no longer adjusts `absptr`. That should fix this case, because every
`pc_begin` here has a relocation. It is untested here, and the commit that changed this
hasn't been identified.

g++ 2.95's exception tables aren't something this analyzer understands anyway, so disabling it
loses nothing on these binaries.
