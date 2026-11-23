package com.example

/**
 * Strictly read-only WebAssembly Entity Struct Field Prober & Schema Sync scripts.
 *
 * Security & observational guarantees:
 * - Never writes to WebAssembly linear memory (`HEAPF32`, `HEAPU32`, `HEAP32`, `HEAPU8`, `HEAP16`).
 * - Never dispatches synthetic touch, pointer, mouse, or keyboard events.
 * - Probes bounded local struct windows (`-4 KB .. +12 KB` + 1-hop sub-struct pointers) in < 5ms
 *   so the browser render loop and WebSocket traffic never stall.
 */
object WasmStructProberScripts {

    private const val PROBER_HEAP_HELPERS = """
        const __spBridge = window.TelemetryBridge;
        const __spResolveHeaps = function() {
          const tryObj = function(obj, prefix) {
            if (!obj || typeof obj !== 'object') return null;
            try {
              const f32 = obj.HEAPF32;
              if (f32 instanceof Float32Array && f32.buffer && f32.buffer.byteLength >= 4) {
                const buf = f32.buffer;
                return {
                  heapF32: f32,
                  heapU32: new Uint32Array(buf),
                  heap32: new Int32Array(buf),
                  heap16: new Int16Array(buf),
                  heapU8: new Uint8Array(buf),
                  label: prefix + 'HEAPF32',
                  byteLength: buf.byteLength
                };
              }
              const h32 = obj.HEAP32 || obj.HEAPU8 || obj.HEAP8;
              if (h32 && ArrayBuffer.isView(h32) && h32.buffer && h32.buffer.byteLength >= 4) {
                const buf = h32.buffer;
                return {
                  heapF32: new Float32Array(buf),
                  heapU32: new Uint32Array(buf),
                  heap32: new Int32Array(buf),
                  heap16: new Int16Array(buf),
                  heapU8: new Uint8Array(buf),
                  label: prefix + 'HEAPF32 (buffer view)',
                  byteLength: buf.byteLength
                };
              }
              const mem = obj.wasmMemory || (obj.asm && obj.asm.memory) || (obj.wasmExports && obj.wasmExports.memory);
              if (typeof WebAssembly !== 'undefined' && mem instanceof WebAssembly.Memory && mem.buffer && mem.buffer.byteLength >= 4) {
                const buf = mem.buffer;
                return {
                  heapF32: new Float32Array(buf),
                  heapU32: new Uint32Array(buf),
                  heap32: new Int32Array(buf),
                  heap16: new Int16Array(buf),
                  heapU8: new Uint8Array(buf),
                  label: prefix + 'wasmMemory',
                  byteLength: buf.byteLength
                };
              }
            } catch (_) {}
            return null;
          };
          const direct = tryObj(window, 'window.');
          if (direct) return direct;
          const mod = tryObj(window.Module, 'window.Module.');
          if (mod) return mod;
          const roots = ['unityInstance', 'game', 'app', 'engine', 'Game', 'App', 'Engine'];
          for (let i = 0; i < roots.length; i++) {
            try {
              const r = window[roots[i]];
              if (!r || typeof r !== 'object') continue;
              const hit = tryObj(r, 'window.' + roots[i] + '.') || (r.Module ? tryObj(r.Module, 'window.' + roots[i] + '.Module.') : null);
              if (hit) return hit;
            } catch (_) {}
          }
          return null;
        };

        const __spEmit = function(cat, obj) {
          try {
            if (__spBridge && typeof __spBridge.logTelemetry === 'function') {
              __spBridge.logTelemetry(cat, JSON.stringify(obj));
            }
          } catch (_) {}
        };

        const __spSignedHex = function(n) {
          const sign = n < 0 ? '-' : '+';
          return sign + '0x' + Math.abs(n).toString(16).toUpperCase();
        };

        const __spReadCleanUtf8 = function(u8, startAddr, maxLen) {
          const len = u8.length;
          if (startAddr <= 0 || startAddr >= len) return null;
          const limit = Math.min(len, startAddr + (maxLen || 48));
          const bytes = [];
          let letters = 0;
          for (let p = startAddr; p < limit; p++) {
            const b = u8[p];
            if (b === 0) break;
            if (b >= 32 && b <= 126) {
              bytes.push(b);
              if ((b >= 65 && b <= 90) || (b >= 97 && b <= 122)) letters++;
            } else if (b >= 0xC2 && b <= 0xF4 && p + 1 < limit) {
              // Allow valid multi-byte UTF-8 letters (accented/international mob names)
              bytes.push(b);
              letters++;
            } else if (b >= 0x80 && b <= 0xBF && bytes.length > 0) {
              bytes.push(b);
            } else {
              return null;
            }
          }
          if (bytes.length < 3 || bytes.length > 44 || letters < 2) return null;
          let text = '';
          try {
            if (typeof TextDecoder !== 'undefined') {
              text = new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(bytes));
            } else {
              for (let k = 0; k < bytes.length; k++) text += String.fromCharCode(bytes[k]);
            }
          } catch (_) {
            return null;
          }
          text = text.trim();
          if (text.length < 3 || text.length > 44) return null;
          // Reject noisy punctuation / paths / shader tokens
          if (/^[\d\W_]+$/.test(text)) return null;
          if (/[\\{}<>;=]/.test(text)) return null;
          if (/^(http|https|glsl|vec|mat|uniform|attribute|void|float|const|function)/i.test(text)) return null;
          let badPunct = 0;
          for (let i = 0; i < text.length; i++) {
            const ch = text.charCodeAt(i);
            const ok = (ch >= 65 && ch <= 90) || (ch >= 97 && ch <= 122) ||
              (ch >= 48 && ch <= 57) || ch === 32 || ch === 45 || ch === 39 ||
              ch === 95 || ch === 46 || ch === 40 || ch === 41 || ch === 91 || ch === 93 || ch > 127;
            if (!ok) badPunct++;
          }
          if (badPunct > 1) return null;
          return text;
        };

        const __spReadField = function(coordAddr, desc, heaps) {
          if (!desc || coordAddr <= 0) return null;
          const byteLen = heaps.byteLength;
          let addr = coordAddr + (desc.relOffsetBytes | 0);
          if (addr <= 0 || addr >= byteLen) return null;

          if (desc.subOffsetBytes !== null && desc.subOffsetBytes !== undefined) {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapU32.length) return null;
            const ptr = heaps.heapU32[addr >>> 2] >>> 0;
            if (ptr < 1024 || ptr >= byteLen) return null;
            addr = ptr + (desc.subOffsetBytes | 0);
            if (addr <= 0 || addr >= byteLen) return null;
          }

          const vt = desc.valueType || 'INT32';
          if (vt === 'UINT8') {
            return heaps.heapU8[addr] >>> 0;
          }
          if (vt === 'INT32') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heap32.length) return null;
            const v = heaps.heap32[addr >>> 2];
            if (v < -100000000 || v > 100000000) return null;
            return v;
          }
          if (vt === 'INT16') {
            if ((addr & 1) !== 0 || (addr >>> 1) >= heaps.heap16.length) return null;
            return heaps.heap16[addr >>> 1];
          }
          if (vt === 'FLOAT32') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapF32.length) return null;
            const fv = heaps.heapF32[addr >>> 2];
            if (!Number.isFinite(fv) || Math.abs(fv) > 100000000) return null;
            return Math.round(fv);
          }
          if (vt === 'INLINE_UTF8') {
            return __spReadCleanUtf8(heaps.heapU8, addr, 48);
          }
          if (vt === 'CHAR_PTR') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapU32.length) return null;
            const strPtr = heaps.heapU32[addr >>> 2] >>> 0;
            if (strPtr < 1024 || strPtr >= byteLen) return null;
            return __spReadCleanUtf8(heaps.heapU8, strPtr, 48);
          }
          if (vt === 'STD_STRING') {
            // Try Emscripten libc++ SSO inline (byte 0 bit 0 == 0 => short len in byte0>>1, data at +1)
            // or long string (ptr at +8 or +0 depending on ABI)
            const b0 = heaps.heapU8[addr];
            if ((b0 & 1) === 0) {
              const ssoLen = b0 >>> 1;
              if (ssoLen >= 3 && ssoLen <= 22 && addr + 1 + ssoLen < byteLen) {
                const s1 = __spReadCleanUtf8(heaps.heapU8, addr + 1, ssoLen + 1);
                if (s1) return s1;
              }
            }
            const trySlots = [8, 0, 4];
            for (let s = 0; s < trySlots.length; s++) {
              const slotAddr = addr + trySlots[s];
              if ((slotAddr & 3) !== 0 || (slotAddr >>> 2) >= heaps.heapU32.length) continue;
              const p = heaps.heapU32[slotAddr >>> 2] >>> 0;
              if (p >= 1024 && p < byteLen) {
                const txt = __spReadCleanUtf8(heaps.heapU8, p, 48);
                if (txt) return txt;
              }
            }
            return __spReadCleanUtf8(heaps.heapU8, addr, 48);
          }
          return null;
        };

        const __spResolveLivePlayerAddr = function(fallbackAddr, heaps) {
          try {
            const pw = window.__wvWasmPtrWatch;
            if (pw && pw.hudPointerId && Array.isArray(pw.chains)) {
              for (let i = 0; i < pw.chains.length; i++) {
                if (pw.chains[i].id === pw.hudPointerId) {
                  let a = pw.chains[i].basePointerAddress >>> 0;
                  const offs = Array.isArray(pw.chains[i].offsets) ? pw.chains[i].offsets : [];
                  let ok = true;
                  for (let k = 0; k < offs.length; k++) {
                    const uIdx = a >>> 2;
                    if (a === 0 || (a & 3) !== 0 || uIdx >= heaps.heapU32.length) { ok = false; break; }
                    const deref = heaps.heapU32[uIdx] >>> 0;
                    const next = deref + (offs[k] | 0);
                    if (deref === 0 || next <= 0 || next >= heaps.byteLength || (next & 3) !== 0) { ok = false; break; }
                    a = next;
                  }
                  if (ok && a > 0) return a;
                }
              }
            }
          } catch (_) {}
          return fallbackAddr > 0 ? fallbackAddr : 0;
        };
    """

    /**
     * Synchronizes the bound [WasmEntityStructSchema] JSON into `window.__wvStructSchema`
     * so both the 200ms Live Pointer Watch and 500ms Live WASM Entity refresh decode
     * HP / MP / FP / Name automatically without interrupting active intervals.
     */
    fun buildSyncStructSchemaScript(schemaJson: String): String = """
        (function() {
          try {
            window.__wvStructSchema = $schemaJson;
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Strictly read-only struct offset probe (or multi-round refinement) for numeric stats
     * (`HP`, `MP`, `FP`, `MaxHP`, `MaxMP`, `MaxFP`) relative to the live player coordinate address.
     *
     * Scans `[-4096 .. +12288]` bytes around `playerCoordAddr` for `INT32`, `FLOAT32`, and `INT16`
     * matches, plus 1-hop sub-struct pointers `*(coordAddr + ptrRel) + subOff`, and cross-samples
     * each candidate on up to 3 tracked nearby entities for instant verification.
     */
    fun buildProbeStatOffsetsScript(
        fieldKind: WasmStructFieldKind,
        playerByteAddress: Long,
        targetValue: Double,
        existingCandidatesJson: String? = null,
        anchorIsTarget: Boolean = false
    ): String {
        val safeAddr = playerByteAddress.coerceAtLeast(0L)
        val safeTarget = if (targetValue.isFinite()) targetValue.toString() else "0"
        val refineJson = existingCandidatesJson?.takeIf { it.isNotBlank() } ?: "null"
        val useLiveTargetExpr = if (anchorIsTarget) "true" else "false"
        return """
        (function() {
          try {
            $PROBER_HEAP_HELPERS
            const startMs = performance.now();
            const heaps = __spResolveHeaps();
            if (!heaps) {
              __spEmit('WASM', {
                stage: 'WASM_STRUCT_PROBE_RESULTS',
                fieldKind: '${fieldKind.name}',
                isRefine: ${existingCandidatesJson != null},
                error: 'Could not locate window.HEAPF32 / WASM heap on this page.',
                candidates: []
              });
              return;
            }

            let playerAddr = 0;
            if ($useLiveTargetExpr) {
              const liveTargetCoord = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedCoordAddr > 0)
                ? window.__wvTargetScan.lastResolvedCoordAddr
                : 0;
              playerAddr = liveTargetCoord > 0 ? liveTargetCoord : $safeAddr;
            } else {
              playerAddr = __spResolveLivePlayerAddr($safeAddr, heaps);
            }
            if (playerAddr <= 0) {
              __spEmit('WASM', {
                stage: 'WASM_STRUCT_PROBE_RESULTS',
                fieldKind: '${fieldKind.name}',
                isRefine: ${existingCandidatesJson != null},
                error: $useLiveTargetExpr
                  ? 'No live selected target locked yet. Select a mob and lock its target slot first.'
                  : 'No live player pointer or locked coordinate address active yet.',
                candidates: []
              });
              return;
            }

            const TARGET = Number($safeTarget);
            const TARGET_INT = Math.round(TARGET);
            const IS_INT_TARGET = Math.abs(TARGET - TARGET_INT) < 0.001;
            const trackedMobs = (window.__wvWasmEntities && Array.isArray(window.__wvWasmEntities.tracked))
              ? window.__wvWasmEntities.tracked.slice(0, 3)
              : [];

            const sampleMobs = function(desc) {
              const previews = [];
              let nonZero = 0;
              for (let m = 0; m < trackedMobs.length; m++) {
                const mob = trackedMobs[m];
                const v = __spReadField(mob.byteAddr, desc, heaps);
                if (v !== null && typeof v === 'number' && Number.isFinite(v)) {
                  if (v > 0) nonZero++;
                  previews.push('#' + (m + 1) + '=' + v);
                }
              }
              return { previews: previews, nonZero: nonZero };
            };

            const findAdjacentMax = function(baseAddr, vt) {
              const stepBytes = (vt === 'INT16') ? 2 : 4;
              const deltas = [stepBytes, -stepBytes, stepBytes * 2];
              for (let d = 0; d < deltas.length; d++) {
                const a = baseAddr + deltas[d];
                if (a <= 0 || a + stepBytes > heaps.byteLength) continue;
                let v = null;
                if (vt === 'INT32' && (a & 3) === 0) v = heaps.heap32[a >>> 2];
                else if (vt === 'FLOAT32' && (a & 3) === 0) {
                  const f = heaps.heapF32[a >>> 2];
                  if (Number.isFinite(f)) v = Math.round(f);
                } else if (vt === 'INT16' && (a & 1) === 0) v = heaps.heap16[a >>> 1];
                if (v !== null && v >= TARGET_INT && v > 0 && v <= Math.max(TARGET_INT * 20, 5000000)) {
                  return { deltaBytes: deltas[d], value: v };
                }
              }
              return null;
            };

            const results = [];
            const seenIds = {};
            const addCandidate = function(relOff, subOff, vt, numVal, resolvedFieldAddr) {
              const id = (subOff === null ? ('D_' + relOff) : ('P_' + relOff + '_' + subOff)) + '_' + vt;
              if (seenIds[id]) return;
              seenIds[id] = true;
              const tag = vt === 'INT32' ? 'i32' : (vt === 'FLOAT32' ? 'f32' : 'i16');
              const expr = subOff === null
                ? (__spSignedHex(relOff) + ' [' + tag + ']')
                : ('*(' + __spSignedHex(relOff) + ')' + __spSignedHex(subOff) + ' [' + tag + ']');
              const desc = { relOffsetBytes: relOff, subOffsetBytes: subOff, valueType: vt };
              const mobInfo = sampleMobs(desc);
              const adj = (subOff === null && resolvedFieldAddr > 0) ? findAdjacentMax(resolvedFieldAddr, vt) : null;
              results.push({
                id: id,
                relOffsetBytes: relOff,
                subOffsetBytes: subOff,
                valueType: vt,
                expression: expr,
                playerDisplayValue: String(numVal),
                playerNumericValue: numVal,
                adjacentMaxOffsetBytes: adj ? (relOff + adj.deltaBytes) : null,
                adjacentMaxValue: adj ? adj.value : null,
                mobPreviews: mobInfo.previews,
                nonZeroMobCount: mobInfo.nonZero
              });
            };

            const prior = $refineJson;
            if (Array.isArray(prior) && prior.length > 0) {
              for (let i = 0; i < prior.length; i++) {
                const c = prior[i];
                const v = __spReadField(playerAddr, c, heaps);
                if (v === null || typeof v !== 'number') continue;
                const matched = Math.abs(v - TARGET) <= (c.valueType === 'FLOAT32' ? 0.6 : 0.01);
                if (matched) {
                  const fieldAddr = (c.subOffsetBytes === null || c.subOffsetBytes === undefined)
                    ? (playerAddr + (c.relOffsetBytes | 0))
                    : 0;
                  addCandidate(c.relOffsetBytes | 0, (c.subOffsetBytes === undefined ? null : c.subOffsetBytes), c.valueType, v, fieldAddr);
                }
              }
            } else {
              const byteLen = heaps.byteLength;
              const h32 = heaps.heap32;
              const hf32 = heaps.heapF32;
              const h16 = heaps.heap16;
              const hu32 = heaps.heapU32;

              // 1. Direct struct window: [-4096 .. +12288] bytes around playerCoordAddr
              const minDirect = Math.max(4, playerAddr - 4096) & ~3;
              const maxDirect = Math.min(byteLen - 4, playerAddr + 12288) & ~3;
              for (let a = minDirect; a <= maxDirect && results.length < 48; a += 4) {
                const rel = a - playerAddr;
                if (rel >= 0 && rel <= 8) continue; // skip X/Y/Z coordinate floats themselves
                const idx32 = a >>> 2;
                if (IS_INT_TARGET && h32[idx32] === TARGET_INT) {
                  addCandidate(rel, null, 'INT32', TARGET_INT, a);
                }
                const fv = hf32[idx32];
                if (Number.isFinite(fv) && Math.abs(fv - TARGET) <= 0.5 && Math.abs(fv) > 0.001) {
                  addCandidate(rel, null, 'FLOAT32', Number(fv.toFixed(2)), a);
                }
              }

              // Also check INT16 in tighter window [-2048 .. +4096] if integer fits in signed 16-bit
              if (IS_INT_TARGET && TARGET_INT > 0 && TARGET_INT <= 32767 && results.length < 48) {
                const min16 = Math.max(4, playerAddr - 2048) & ~1;
                const max16 = Math.min(byteLen - 2, playerAddr + 4096) & ~1;
                for (let a = min16; a <= max16 && results.length < 56; a += 2) {
                  const rel = a - playerAddr;
                  if (rel >= 0 && rel <= 10) continue;
                  if ((a & 3) === 0 && h32[a >>> 2] === TARGET_INT) continue; // already reported as INT32
                  if (h16[a >>> 1] === TARGET_INT) {
                    addCandidate(rel, null, 'INT16', TARGET_INT, a);
                  }
                }
              }

              // 2. 1-hop sub-struct pointer scan: pointers in [-2048 .. +4096] -> inspect [0 .. +512]
              const minPtr = Math.max(4, playerAddr - 2048) & ~3;
              const maxPtr = Math.min(byteLen - 4, playerAddr + 4096) & ~3;
              let ptrScanned = 0;
              for (let pa = minPtr; pa <= maxPtr && results.length < 64 && ptrScanned < 400; pa += 4) {
                const ptr = hu32[pa >>> 2] >>> 0;
                if (ptr < 4096 || (ptr & 3) !== 0 || ptr + 512 >= byteLen) continue;
                if (Math.abs(ptr - playerAddr) < 128) continue;
                ptrScanned++;
                const ptrRel = pa - playerAddr;
                for (let sub = 0; sub <= 512 && results.length < 64; sub += 4) {
                  const subAddr = ptr + sub;
                  const idx = subAddr >>> 2;
                  if (IS_INT_TARGET && h32[idx] === TARGET_INT) {
                    addCandidate(ptrRel, sub, 'INT32', TARGET_INT, subAddr);
                  }
                  const fv = hf32[idx];
                  if (Number.isFinite(fv) && Math.abs(fv - TARGET) <= 0.5 && Math.abs(fv) > 0.001) {
                    addCandidate(ptrRel, sub, 'FLOAT32', Number(fv.toFixed(2)), subAddr);
                  }
                }
              }
            }

            // Rank: prefer candidates that also produce non-zero plausible values on mobs, then direct INT32, then closest offset
            results.sort(function(a, b) {
              if (a.nonZeroMobCount !== b.nonZeroMobCount) return b.nonZeroMobCount - a.nonZeroMobCount;
              const aDirect = a.subOffsetBytes === null ? 0 : 1;
              const bDirect = b.subOffsetBytes === null ? 0 : 1;
              if (aDirect !== bDirect) return aDirect - bDirect;
              const vtRank = function(v) { return v === 'INT32' ? 0 : (v === 'FLOAT32' ? 1 : 2); };
              if (vtRank(a.valueType) !== vtRank(b.valueType)) return vtRank(a.valueType) - vtRank(b.valueType);
              return Math.abs(a.relOffsetBytes) - Math.abs(b.relOffsetBytes);
            });

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __spEmit('WASM', {
              stage: 'WASM_STRUCT_PROBE_RESULTS',
              fieldKind: '${fieldKind.name}',
              isRefine: ${existingCandidatesJson != null},
              durationMs: durationMs,
              playerByteAddress: playerAddr,
              targetValue: TARGET,
              totalFound: results.length,
              candidates: results.slice(0, 32)
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Struct stat probe error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Strictly read-only Name Auto-Prober and Name-Text Search across the player struct and
     * discovered nearby entity structs.
     *
     * Inspects `INLINE_UTF8`, 1-hop `CHAR_PTR`, and `STD_STRING` layouts in `[-2048 .. +8192]`
     * relative to each entity's coordinate address. If [nameQuery] is non-blank, filters to
     * offsets whose decoded text matches [nameQuery] (case-insensitive) on either the player
     * or any nearby mob.
     */
    fun buildProbeNameOffsetsScript(
        playerByteAddress: Long,
        nameQuery: String = "",
        anchorIsTarget: Boolean = false
    ): String {
        val safeAddr = playerByteAddress.coerceAtLeast(0L)
        val escapedQuery = quoteJsString(nameQuery.trim())
        val useLiveTargetExpr = if (anchorIsTarget) "true" else "false"
        return """
        (function() {
          try {
            $PROBER_HEAP_HELPERS
            const startMs = performance.now();
            const heaps = __spResolveHeaps();
            if (!heaps) {
              __spEmit('WASM', {
                stage: 'WASM_STRUCT_PROBE_RESULTS',
                fieldKind: 'NAME',
                isRefine: false,
                error: 'Could not locate window.HEAPF32 / WASM heap on this page.',
                candidates: []
              });
              return;
            }

            let playerAddr = 0;
            if ($useLiveTargetExpr) {
              const liveTargetCoord = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedCoordAddr > 0)
                ? window.__wvTargetScan.lastResolvedCoordAddr
                : 0;
              playerAddr = liveTargetCoord > 0 ? liveTargetCoord : $safeAddr;
            } else {
              playerAddr = __spResolveLivePlayerAddr($safeAddr, heaps);
            }
            const trackedMobs = (window.__wvWasmEntities && Array.isArray(window.__wvWasmEntities.tracked))
              ? window.__wvWasmEntities.tracked.slice(0, 5)
              : [];
            if (playerAddr <= 0 && trackedMobs.length === 0) {
              __spEmit('WASM', {
                stage: 'WASM_STRUCT_PROBE_RESULTS',
                fieldKind: 'NAME',
                isRefine: false,
                error: 'Need an active player/target pointer or scanned entities before probing names.',
                candidates: []
              });
              return;
            }

            const QUERY = ($escapedQuery || '').toLowerCase();
            const byteLen = heaps.byteLength;
            const u8 = heaps.heapU8;
            const hu32 = heaps.heapU32;
            const bucketMap = {};

            const recordHit = function(relOff, vt, entityLabel, text) {
              const key = relOff + '_' + vt;
              let b = bucketMap[key];
              if (!b) {
                const tag = vt === 'INLINE_UTF8' ? 'utf8' : (vt === 'CHAR_PTR' ? 'char*' : 'std::str');
                b = {
                  id: 'N_' + relOff + '_' + vt,
                  relOffsetBytes: relOff,
                  subOffsetBytes: null,
                  valueType: vt,
                  expression: __spSignedHex(relOff) + ' [' + tag + ']',
                  playerValue: '',
                  mobPreviews: [],
                  matchedQuery: false,
                  distinctNames: {}
                };
                bucketMap[key] = b;
              }
              if (entityLabel === 'PLAYER') {
                b.playerValue = text;
              } else if (b.mobPreviews.length < 4) {
                b.mobPreviews.push(entityLabel + '="' + text + '"');
              }
              b.distinctNames[text] = true;
              if (QUERY.length > 0 && text.toLowerCase().indexOf(QUERY) !== -1) {
                b.matchedQuery = true;
              }
            };

            const probeAnchor = function(anchorAddr, entityLabel) {
              if (!anchorAddr || anchorAddr <= 0 || anchorAddr >= byteLen) return;
              const minAddr = Math.max(4, anchorAddr - 2048) & ~3;
              const maxAddr = Math.min(byteLen - 4, anchorAddr + 8192) & ~3;

              let inlineSkipUntil = 0;
              for (let a = minAddr; a <= maxAddr; a += 4) {
                const rel = a - anchorAddr;
                if (rel >= 0 && rel <= 12) continue;

                // 1. Inline UTF-8/ASCII buffer starting at 4-byte boundary
                if (a >= inlineSkipUntil) {
                  const inlineStr = __spReadCleanUtf8(u8, a, 40);
                  if (inlineStr) {
                    recordHit(rel, 'INLINE_UTF8', entityLabel, inlineStr);
                    inlineSkipUntil = a + Math.max(4, inlineStr.length);
                  }
                }

                // 2. 1-hop char* pointer -> HEAPU8 null-terminated UTF-8 string
                const ptr = hu32[a >>> 2] >>> 0;
                if (ptr >= 1024 && ptr + 4 < byteLen) {
                  const ptrStr = __spReadCleanUtf8(u8, ptr, 44);
                  if (ptrStr) {
                    recordHit(rel, 'CHAR_PTR', entityLabel, ptrStr);
                  }
                }

                // 3. Emscripten libc++ std::string SSO inline (byte0 even, len in byte0>>1, text at a+1)
                const b0 = u8[a];
                if ((b0 & 1) === 0) {
                  const ssoLen = b0 >>> 1;
                  if (ssoLen >= 3 && ssoLen <= 22 && a + 1 + ssoLen < byteLen) {
                    const ssoStr = __spReadCleanUtf8(u8, a + 1, ssoLen + 1);
                    if (ssoStr && ssoStr.length === ssoLen) {
                      recordHit(rel, 'STD_STRING', entityLabel, ssoStr);
                    }
                  }
                }
              }
            };

            if (playerAddr > 0) {
              probeAnchor(playerAddr, 'PLAYER');
            }
            for (let m = 0; m < trackedMobs.length; m++) {
              probeAnchor(trackedMobs[m].byteAddr, '#' + (m + 1));
            }

            const rawBuckets = Object.keys(bucketMap).map(function(k) { return bucketMap[k]; });
            const filtered = [];
            for (let i = 0; i < rawBuckets.length; i++) {
              const b = rawBuckets[i];
              if (QUERY.length > 0 && !b.matchedQuery) continue;
              const mobCount = b.mobPreviews.length;
              const distinctCount = Object.keys(b.distinctNames).length;
              filtered.push({
                id: b.id,
                relOffsetBytes: b.relOffsetBytes,
                subOffsetBytes: null,
                valueType: b.valueType,
                expression: b.expression,
                playerDisplayValue: b.playerValue || '(mob-only)',
                playerNumericValue: null,
                adjacentMaxOffsetBytes: null,
                adjacentMaxValue: null,
                mobPreviews: b.mobPreviews,
                nonZeroMobCount: mobCount,
                distinctCount: distinctCount,
                matchedQuery: b.matchedQuery
              });
            }

            filtered.sort(function(a, b) {
              if (a.matchedQuery !== b.matchedQuery) return a.matchedQuery ? -1 : 1;
              if (a.nonZeroMobCount !== b.nonZeroMobCount) return b.nonZeroMobCount - a.nonZeroMobCount;
              if (a.distinctCount !== b.distinctCount) return b.distinctCount - a.distinctCount;
              return Math.abs(a.relOffsetBytes) - Math.abs(b.relOffsetBytes);
            });

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __spEmit('WASM', {
              stage: 'WASM_STRUCT_PROBE_RESULTS',
              fieldKind: 'NAME',
              isRefine: false,
              durationMs: durationMs,
              playerByteAddress: playerAddr,
              nameQuery: $escapedQuery,
              totalFound: filtered.length,
              candidates: filtered.slice(0, 32)
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Struct name probe error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Strictly read-only Aggro Status Prober around the active Selected Target mob's struct.
     *
     * Supports 2-state differential narrowing:
     * - [isAggroedNow] = `false` (Passive / Idle): searches for `UINT8 == 0` or `INT32 == 0` slots in `[-2048 .. +6144]`.
     * - [isAggroedNow] = `true` (Aggroed / Attacking): searches for `UINT8` (`1..8`), `INT32` (`1..16`),
     *   or `INT32/UINT32` slots pointing to the player struct (`±512B` of `playerByteAddress`).
     * Refining across Passive -> Aggroed (or vice versa) isolates the exact Aggro flag or target-of-target pointer!
     */
    fun buildProbeAggroOffsetsScript(
        targetAnchorByteAddress: Long,
        playerByteAddress: Long,
        isAggroedNow: Boolean,
        existingCandidatesJson: String? = null
    ): String {
        val safeTargetAddr = targetAnchorByteAddress.coerceAtLeast(0L)
        val safePlayerAddr = playerByteAddress.coerceAtLeast(0L)
        val refineJson = existingCandidatesJson?.takeIf { it.isNotBlank() } ?: "null"
        return """
        (function() {
          try {
            $PROBER_HEAP_HELPERS
            const startMs = performance.now();
            const heaps = __spResolveHeaps();
            if (!heaps) {
              __spEmit('WASM', {
                stage: 'WASM_STRUCT_PROBE_RESULTS',
                fieldKind: 'AGGRO',
                isRefine: ${existingCandidatesJson != null},
                error: 'Could not locate WASM heap on this page.',
                candidates: []
              });
              return;
            }

            const liveTargetCoord = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedCoordAddr > 0)
              ? window.__wvTargetScan.lastResolvedCoordAddr
              : 0;
            const anchorAddr = liveTargetCoord > 0 ? liveTargetCoord : $safeTargetAddr;
            if (anchorAddr <= 0) {
              __spEmit('WASM', {
                stage: 'WASM_STRUCT_PROBE_RESULTS',
                fieldKind: 'AGGRO',
                isRefine: ${existingCandidatesJson != null},
                error: 'Lock a Selected Target mob first so Aggro offsets can be probed on that mob.',
                candidates: []
              });
              return;
            }

            const playerAddr = __spResolveLivePlayerAddr($safePlayerAddr, heaps);
            const IS_AGGRO = ${if (isAggroedNow) "true" else "false"};
            const trackedMobs = (window.__wvWasmEntities && Array.isArray(window.__wvWasmEntities.tracked))
              ? window.__wvWasmEntities.tracked.slice(0, 3)
              : [];

            const sampleMobs = function(desc) {
              const previews = [];
              let activeCount = 0;
              for (let m = 0; m < trackedMobs.length; m++) {
                const v = __spReadField(trackedMobs[m].byteAddr, desc, heaps);
                if (v !== null && typeof v === 'number') {
                  if (v !== 0) activeCount++;
                  previews.push('#' + (m + 1) + '=' + (v === 0 ? '0' : String(v)));
                }
              }
              return { previews: previews, activeCount: activeCount };
            };

            const results = [];
            const seen = {};
            const addCand = function(relOff, vt, rawVal, scoreBoost) {
              const id = 'A_' + relOff + '_' + vt;
              if (seen[id]) return;
              seen[id] = true;
              const tag = vt === 'UINT8' ? 'u8' : 'i32';
              const expr = __spSignedHex(relOff) + ' [' + tag + ']';
              const desc = { relOffsetBytes: relOff, subOffsetBytes: null, valueType: vt };
              const mobInfo = sampleMobs(desc);
              const disp = (rawVal === 0)
                ? '0 (PASSIVE)'
                : (playerAddr > 0 && Math.abs(rawVal - playerAddr) <= 512)
                  ? ('PLAYER_PTR (' + rawVal + ')')
                  : (String(rawVal) + ' (AGGRO)');
              results.push({
                id: id,
                relOffsetBytes: relOff,
                subOffsetBytes: null,
                valueType: vt,
                expression: expr,
                playerDisplayValue: disp,
                playerNumericValue: rawVal,
                adjacentMaxOffsetBytes: null,
                adjacentMaxValue: null,
                mobPreviews: mobInfo.previews,
                nonZeroMobCount: scoreBoost + (IS_AGGRO ? (mobInfo.previews.length - mobInfo.activeCount) : mobInfo.activeCount)
              });
            };

            const prior = $refineJson;
            if (Array.isArray(prior) && prior.length > 0) {
              for (let i = 0; i < prior.length; i++) {
                const c = prior[i];
                const v = __spReadField(anchorAddr, c, heaps);
                if (v === null || typeof v !== 'number') continue;
                if (!IS_AGGRO) {
                  if (v === 0) addCand(c.relOffsetBytes | 0, c.valueType, 0, 10);
                } else {
                  const isPlayerPtr = (playerAddr > 0 && Math.abs(v - playerAddr) <= 512);
                  const isFlag = (v >= 1 && v <= 16);
                  if (isPlayerPtr || isFlag) {
                    addCand(c.relOffsetBytes | 0, c.valueType, v, isPlayerPtr ? 50 : 15);
                  }
                }
              }
            } else {
              const byteLen = heaps.byteLength;
              const h32 = heaps.heap32;
              const hu32 = heaps.heapU32;
              const u8 = heaps.heapU8;
              const minA = Math.max(4, anchorAddr - 2048) & ~3;
              const maxA = Math.min(byteLen - 4, anchorAddr + 6144) & ~3;

              for (let a = minA; a <= maxA && results.length < 96; a += 4) {
                const rel = a - anchorAddr;
                if (rel >= 0 && rel <= 12) continue;
                const u32Val = hu32[a >>> 2] >>> 0;
                const i32Val = h32[a >>> 2];

                if (IS_AGGRO) {
                  if (playerAddr > 0 && u32Val >= 4096 && Math.abs(u32Val - playerAddr) <= 512) {
                    addCand(rel, 'INT32', i32Val, 50);
                  } else if (i32Val >= 1 && i32Val <= 8) {
                    addCand(rel, 'INT32', i32Val, 10);
                  }
                  const b0 = u8[a];
                  if (b0 === 1 && i32Val !== 1) {
                    addCand(rel, 'UINT8', 1, 5);
                  }
                } else {
                  if (i32Val === 0) {
                    addCand(rel, 'INT32', 0, 4);
                  } else if (u8[a] === 0 && results.length < 80) {
                    addCand(rel, 'UINT8', 0, 2);
                  }
                }
              }
            }

            results.sort(function(a, b) {
              if (a.nonZeroMobCount !== b.nonZeroMobCount) return b.nonZeroMobCount - a.nonZeroMobCount;
              return Math.abs(a.relOffsetBytes) - Math.abs(b.relOffsetBytes);
            });

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __spEmit('WASM', {
              stage: 'WASM_STRUCT_PROBE_RESULTS',
              fieldKind: 'AGGRO',
              isRefine: ${existingCandidatesJson != null},
              durationMs: durationMs,
              playerByteAddress: anchorAddr,
              isAggroedNow: IS_AGGRO,
              totalFound: results.length,
              candidates: results.slice(0, 32)
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Struct aggro probe error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    private fun quoteJsString(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }
}
