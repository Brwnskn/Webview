package com.example

/**
 * Strictly read-only WebAssembly Selected-Target Differential Scanner and Live 200ms Target Watcher.
 *
 * Security & observational guarantees:
 * - Strictly READ-ONLY: NEVER writes to WebAssembly linear memory (`HEAPU32`, `HEAP32`, `HEAPF32`, `HEAPU8`, `HEAP16`).
 * - NEVER dispatches synthetic mouse, pointer, touch, or keyboard events, and NEVER invokes in-game targeting or movement functions.
 * - Uses non-blocking 1 MB async slices (`setTimeout(resolve, 12)`) during full-heap scans so gameplay stays smooth.
 */
object WasmSelectedTargetScripts {

    private const val TARGET_HEAP_HELPERS = """
        const __tsBridge = window.TelemetryBridge;
        const __tsResolveHeaps = function() {
          const tryObj = function(obj, prefix) {
            if (!obj || typeof obj !== 'object') return null;
            try {
              if (obj.HEAPF32 instanceof Float32Array && obj.HEAPF32.buffer && obj.HEAPF32.buffer.byteLength >= 4) {
                const buf = obj.HEAPF32.buffer;
                return {
                  heapF32: obj.HEAPF32,
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

        const __tsEmit = function(cat, obj) {
          try {
            if (__tsBridge && typeof __tsBridge.logTelemetry === 'function') {
              __tsBridge.logTelemetry(cat, JSON.stringify(obj));
            }
          } catch (_) {}
        };

        const __tsStatus = function(status, detail) {
          try {
            if (__tsBridge && typeof __tsBridge.updateScanStatus === 'function') {
              __tsBridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __tsYield = function(ms) {
          return new Promise(function(resolve) { setTimeout(resolve, ms || 12); });
        };

        const __tsFormatHex = function(n) {
          return '0x' + (n >>> 0).toString(16).toUpperCase().padStart(8, '0');
        };

        const __tsReadUtf8 = function(u8, startAddr, maxLen) {
          if (!u8 || startAddr <= 0 || startAddr >= u8.length) return null;
          const limit = Math.min(u8.length, startAddr + (maxLen || 48));
          const bytes = [];
          let letters = 0;
          for (let p = startAddr; p < limit; p++) {
            const b = u8[p];
            if (b === 0) break;
            if (b >= 32 && b <= 126) {
              bytes.push(b);
              if ((b >= 65 && b <= 90) || (b >= 97 && b <= 122)) letters++;
            } else if (b >= 0xC2 && b <= 0xF4 && p + 1 < limit) {
              bytes.push(b);
              letters++;
            } else if (b >= 0x80 && b <= 0xBF && bytes.length > 0) {
              bytes.push(b);
            } else {
              return null;
            }
          }
          if (bytes.length < 2 || bytes.length > 44 || letters < 1) return null;
          try {
            const txt = (typeof TextDecoder !== 'undefined')
              ? new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(bytes))
              : String.fromCharCode.apply(null, bytes);
            const cleaned = txt.trim();
            return (cleaned.length >= 2 && cleaned.length <= 44) ? cleaned : null;
          } catch (_) {
            return null;
          }
        };

        const __tsReadSchemaField = function(coordAddr, desc, heaps) {
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
            return (v >= 0 && v <= 100000000) ? v : null;
          }
          if (vt === 'INT16') {
            if ((addr & 1) !== 0 || (addr >>> 1) >= heaps.heap16.length) return null;
            const v = heaps.heap16[addr >>> 1];
            return (v >= 0 && v <= 32767) ? v : null;
          }
          if (vt === 'FLOAT32') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapF32.length) return null;
            const fv = heaps.heapF32[addr >>> 2];
            return (Number.isFinite(fv) && fv >= 0 && fv <= 100000000) ? Math.round(fv) : null;
          }
          if (vt === 'INLINE_UTF8') return __tsReadUtf8(heaps.heapU8, addr, 48);
          if (vt === 'CHAR_PTR') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapU32.length) return null;
            const sp = heaps.heapU32[addr >>> 2] >>> 0;
            if (sp < 1024 || sp >= byteLen) return null;
            return __tsReadUtf8(heaps.heapU8, sp, 48);
          }
          if (vt === 'STD_STRING') {
            const b0 = heaps.heapU8[addr];
            if ((b0 & 1) === 0) {
              const slen = b0 >>> 1;
              if (slen >= 2 && slen <= 22 && addr + 1 + slen < byteLen) {
                const s1 = __tsReadUtf8(heaps.heapU8, addr + 1, slen + 1);
                if (s1) return s1;
              }
            }
            const slots = [8, 0, 4];
            for (let s = 0; s < slots.length; s++) {
              const sa = addr + slots[s];
              if ((sa & 3) !== 0 || (sa >>> 2) >= heaps.heapU32.length) continue;
              const p = heaps.heapU32[sa >>> 2] >>> 0;
              if (p >= 1024 && p < byteLen) {
                const t = __tsReadUtf8(heaps.heapU8, p, 48);
                if (t) return t;
              }
            }
            return __tsReadUtf8(heaps.heapU8, addr, 48);
          }
          return null;
        };

        const __tsReadSchema = function(coordAddr, heaps) {
          const schema = window.__wvStructSchema;
          if (!schema || typeof schema !== 'object' || coordAddr <= 0) return {};
          const out = {};
          const keys = ['hp', 'maxHp', 'mp', 'maxMp', 'fp', 'maxFp', 'name', 'aggro'];
          for (let i = 0; i < keys.length; i++) {
            const k = keys[i];
            if (schema[k]) {
              const v = __tsReadSchemaField(coordAddr, schema[k], heaps);
              if (v !== null && v !== undefined) out[k] = v;
            }
          }
          return out;
        };

        const __tsResolveLivePlayer = function(fallbackAddr, heaps) {
          let addr = fallbackAddr > 0 ? fallbackAddr : 0;
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
                  if (ok && a > 0) { addr = a; break; }
                }
              }
            }
          } catch (_) {}
          let px = 0, py = 0, pz = 0, hasCoords = false;
          if (addr > 0 && ((addr >>> 2) + 2) < heaps.heapF32.length) {
            const i = addr >>> 2;
            const fx = heaps.heapF32[i], fy = heaps.heapF32[i + 1], fz = heaps.heapF32[i + 2];
            if (Number.isFinite(fx) && Number.isFinite(fy) && Number.isFinite(fz)) {
              px = fx; py = fy; pz = fz; hasCoords = true;
            }
          }
          return { addr: addr, x: px, y: py, z: pz, hasCoords: hasCoords };
        };

        const __tsMatchEntityAndCoords = function(targetVal, playerInfo, heaps) {
          const tracked = (window.__wvWasmEntities && Array.isArray(window.__wvWasmEntities.tracked))
            ? window.__wvWasmEntities.tracked
            : [];
          const structOff = (window.__wvWasmEntities && window.__wvWasmEntities.structOffset)
            ? (window.__wvWasmEntities.structOffset | 0)
            : 0;

          // 1. Check direct/near match against tracked entities (structBase or byteAddr within ±1024B)
          for (let i = 0; i < tracked.length; i++) {
            const t = tracked[i];
            if (targetVal === t.structBase || targetVal === t.byteAddr ||
                Math.abs(targetVal - t.byteAddr) <= 1024 ||
                (t.id && String(targetVal) === String(t.id))) {
              const fIdx = t.byteAddr >>> 2;
              const x = (fIdx + 2 < heaps.heapF32.length) ? heaps.heapF32[fIdx] : null;
              const y = (fIdx + 2 < heaps.heapF32.length) ? heaps.heapF32[fIdx + 1] : null;
              const z = (fIdx + 2 < heaps.heapF32.length) ? heaps.heapF32[fIdx + 2] : null;
              return {
                matchedEntityId: t.id || null,
                matchedEntityName: t.name || null,
                coordAddr: t.byteAddr,
                x: x, y: y, z: z
              };
            }
          }

          // 2. If targetVal is a plausible heap pointer, locate nearby world Vec3 inside [targetVal - 256 .. targetVal + 768]
          if (targetVal >= 4096 && (targetVal & 3) === 0 && targetVal + 12 < heaps.byteLength && playerInfo.hasCoords) {
            const hf32 = heaps.heapF32;
            const tryOffsets = [];
            if (structOff > 0) tryOffsets.push(structOff);
            tryOffsets.push(0);
            for (let off = 4; off <= 768; off += 4) tryOffsets.push(off);
            for (let off = -4; off >= -256; off -= 4) tryOffsets.push(off);

            for (let oi = 0; oi < tryOffsets.length; oi++) {
              const candAddr = targetVal + tryOffsets[oi];
              if (candAddr <= 0 || candAddr + 12 > heaps.byteLength || (candAddr & 3) !== 0) continue;
              const idx = candAddr >>> 2;
              const vx = hf32[idx], vy = hf32[idx + 1], vz = hf32[idx + 2];
              if (!Number.isFinite(vx) || !Number.isFinite(vy) || !Number.isFinite(vz)) continue;
              const dx = vx - playerInfo.x, dy = vy - playerInfo.y, dz = vz - playerInfo.z;
              const d2 = dx * dx + dy * dy + dz * dz;
              if (d2 >= 0.25 && d2 <= 250000 && Math.abs(dy) <= 120) {
                return {
                  matchedEntityId: null,
                  matchedEntityName: null,
                  coordAddr: candAddr,
                  x: vx, y: vy, z: vz
                };
              }
            }
          }

          return { matchedEntityId: null, matchedEntityName: null, coordAddr: 0, x: null, y: null, z: null };
        };

        const __tsState = (window.__wvTargetScan = window.__wvTargetScan || {
          scanSeq: 0,
          round: 0,
          slotIndices: null,
          prevValues: null,
          prevCount: 0,
          lockedSlotAddr: 0,
          pollId: null,
          tick: 0,
          lastResolvedAddr: 0,
          lastResolvedCoordAddr: 0
        });
    """

    /**
     * Step 1 of Selected Target Discovery:
     * User selects Mob A in-game, then runs this non-blocking 1 MB sliced read-only scan over
     * `HEAPU32` to capture all candidate target pointer / entity-ID slots.
     */
    fun buildCaptureSelectedTargetScript(playerByteAddress: Long): String {
        val safePlayerAddr = playerByteAddress.coerceAtLeast(0L)
        return """
        (async function() {
          try {
            $TARGET_HEAP_HELPERS
            const heaps = __tsResolveHeaps();
            if (!heaps) {
              __tsEmit('WASM', {
                stage: 'WASM_TARGET_SCAN_RESULT',
                round: 0,
                error: 'Could not locate WASM heap (window.HEAPF32) on this page.',
                totalCandidates: 0,
                candidates: []
              });
              return;
            }

            const mySeq = ++__tsState.scanSeq;
            const startMs = performance.now();
            const hu32 = heaps.heapU32;
            const len = hu32.length;
            const byteLen = heaps.byteLength;
            const playerInfo = __tsResolveLivePlayer($safePlayerAddr, heaps);
            const playerAddr = playerInfo.addr;
            const tracked = (window.__wvWasmEntities && Array.isArray(window.__wvWasmEntities.tracked))
              ? window.__wvWasmEntities.tracked
              : [];

            // Build quick lookup range around tracked entities for prioritization
            let minEnt = byteLen, maxEnt = 0;
            for (let i = 0; i < tracked.length; i++) {
              if (tracked[i].structBase < minEnt) minEnt = tracked[i].structBase;
              if (tracked[i].byteAddr > maxEnt) maxEnt = tracked[i].byteAddr;
            }

            __tsStatus('RUNNING', 'Target Finder: capturing candidate pointer slots (Mob A)…');

            const MAX_SLOTS = 900000;
            const capIdx = new Int32Array(MAX_SLOTS);
            const capVal = new Uint32Array(MAX_SLOTS);
            let count = 0;

            const CHUNK = 262144; // 1 MB per slice
            const START_IDX = len > 4096 ? 64 : 0;
            const playerMin = playerAddr > 4096 ? (playerAddr - 4096) : 0;
            const playerMax = playerAddr > 0 ? Math.min(byteLen - 4, playerAddr + 12288) : 0;

            for (let base = START_IDX; base < len && count < MAX_SLOTS; base += CHUNK) {
              if (mySeq !== __tsState.scanSeq) return;
              const end = Math.min(base + CHUNK, len);
              for (let i = base; i < end && count < MAX_SLOTS; i++) {
                const v = hu32[i] >>> 0;
                if (v === 0) continue;
                const slotAddr = i * 4;
                const isPtr = (v >= 4096 && v < byteLen && (v & 3) === 0);
                const nearPlayer = (playerAddr > 0 && slotAddr >= playerMin && slotAddr <= playerMax);
                // Keep valid heap pointers anywhere in memory + plausible small entity IDs inside player struct
                if (isPtr || (nearPlayer && v > 0 && v < 100000000)) {
                  capIdx[count] = i;
                  capVal[count] = v;
                  count++;
                }
              }
              if (end < len && count < MAX_SLOTS) {
                await __tsYield(12);
              }
            }

            if (mySeq !== __tsState.scanSeq) return;

            __tsState.round = 1;
            __tsState.slotIndices = capIdx.subarray(0, count);
            __tsState.prevValues = capVal.subarray(0, count);
            __tsState.prevCount = count;

            // Surface top entity-matching slots immediately if any point straight at a discovered entity
            const immediateMatches = [];
            for (let i = 0; i < count && immediateMatches.length < 24; i++) {
              const slotAddr = capIdx[i] * 4;
              const v = capVal[i] >>> 0;
              let hitEnt = null;
              for (let e = 0; e < tracked.length; e++) {
                if (v === tracked[e].structBase || v === tracked[e].byteAddr) {
                  hitEnt = tracked[e];
                  break;
                }
              }
              if (hitEnt) {
                const nearOff = (playerAddr > 0 && Math.abs(slotAddr - playerAddr) <= 16384) ? (slotAddr - playerAddr) : null;
                const decoded = __tsReadSchema(hitEnt.byteAddr, heaps);
                immediateMatches.push({
                  slotByteAddress: slotAddr,
                  slotHex: __tsFormatHex(slotAddr),
                  currentValue: v,
                  currentHex: __tsFormatHex(v),
                  isPointerLike: true,
                  nearPlayerOffsetBytes: nearOff,
                  matchedEntityId: hitEnt.id || null,
                  matchedEntityName: hitEnt.name || null,
                  resolvedCoordAddress: hitEnt.byteAddr,
                  previewName: decoded.name || null,
                  previewHp: (typeof decoded.hp === 'number') ? decoded.hp : null,
                  previewMaxHp: (typeof decoded.maxHp === 'number') ? decoded.maxHp : null,
                  previewAggroRaw: (typeof decoded.aggro === 'number') ? decoded.aggro : null
                });
              }
            }

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __tsStatus('COMPLETED', 'Target Capture #1: ' + count + ' slots (' + durationMs + 'ms)');
            __tsEmit('WASM', {
              stage: 'WASM_TARGET_SCAN_RESULT',
              action: 'CAPTURE',
              round: 1,
              durationMs: durationMs,
              totalCandidates: count,
              candidates: immediateMatches
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Target capture error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 2..N of Selected Target Discovery:
     * Narrow captured candidate slots strictly read-only after selecting a different mob,
     * keeping the same mob, deselecting (`0` / `0xFFFFFFFF`), or selecting another mob of the same type.
     */
    fun buildFilterSelectedTargetScript(
        mode: TargetScanFilterMode,
        playerByteAddress: Long
    ): String {
        val safePlayerAddr = playerByteAddress.coerceAtLeast(0L)
        return """
        (async function() {
          try {
            $TARGET_HEAP_HELPERS
            const heaps = __tsResolveHeaps();
            if (!heaps || !__tsState.slotIndices || __tsState.prevCount <= 0) {
              __tsEmit('WASM', {
                stage: 'WASM_TARGET_SCAN_RESULT',
                action: '${mode.wireName}',
                round: __tsState.round || 0,
                error: 'Capture Mob A first before running a filter pass.',
                totalCandidates: 0,
                candidates: []
              });
              return;
            }

            const mySeq = ++__tsState.scanSeq;
            const startMs = performance.now();
            const hu32 = heaps.heapU32;
            const len = hu32.length;
            const byteLen = heaps.byteLength;
            const playerInfo = __tsResolveLivePlayer($safePlayerAddr, heaps);
            const playerAddr = playerInfo.addr;
            const MODE = '${mode.wireName}';

            const prevIdx = __tsState.slotIndices;
            const prevVal = __tsState.prevValues;
            const prevCount = __tsState.prevCount;

            const nextIdx = new Int32Array(prevCount);
            const nextVal = new Uint32Array(prevCount);
            let nextCount = 0;

            const CHUNK = 262144;
            for (let base = 0; base < prevCount; base += CHUNK) {
              if (mySeq !== __tsState.scanSeq) return;
              const end = Math.min(base + CHUNK, prevCount);
              for (let i = base; i < end; i++) {
                const slotIdx = prevIdx[i];
                if (slotIdx < 0 || slotIdx >= len) continue;
                const oldV = prevVal[i] >>> 0;
                const curV = hu32[slotIdx] >>> 0;
                let keep = false;

                if (MODE === 'CHANGED_NON_ZERO' || MODE === 'SAME_TYPE_RESELECT') {
                  if (curV !== 0 && curV !== 0xFFFFFFFF && curV !== oldV) {
                    const wasPtr = (oldV >= 4096 && oldV < byteLen && (oldV & 3) === 0);
                    const isPtr = (curV >= 4096 && curV < byteLen && (curV & 3) === 0);
                    // Keep type consistency (pointer -> pointer, or ID -> ID)
                    if (wasPtr === isPtr) keep = true;
                  }
                } else if (MODE === 'UNCHANGED') {
                  keep = (curV === oldV);
                } else if (MODE === 'DESELECTED_ZERO') {
                  keep = ((curV === 0 || curV === 0xFFFFFFFF) && oldV !== 0 && oldV !== 0xFFFFFFFF);
                }

                if (keep) {
                  nextIdx[nextCount] = slotIdx;
                  nextVal[nextCount] = curV;
                  nextCount++;
                }
              }
              if (end < prevCount) {
                await __tsYield(10);
              }
            }

            if (mySeq !== __tsState.scanSeq) return;

            __tsState.round = (__tsState.round || 1) + 1;
            __tsState.slotIndices = nextIdx.subarray(0, nextCount);
            __tsState.prevValues = nextVal.subarray(0, nextCount);
            __tsState.prevCount = nextCount;

            // Rank surviving candidates (up to 400 evaluated in detail)
            const scored = [];
            const inspectLimit = Math.min(nextCount, 400);
            for (let i = 0; i < inspectLimit; i++) {
              const slotAddr = nextIdx[i] * 4;
              const curV = nextVal[i] >>> 0;
              const isPtr = (curV >= 4096 && curV < byteLen && (curV & 3) === 0);
              const nearOff = (playerAddr > 0 && Math.abs(slotAddr - playerAddr) <= 16384) ? (slotAddr - playerAddr) : null;
              const match = (curV > 0 && curV !== 0xFFFFFFFF)
                ? __tsMatchEntityAndCoords(curV, playerInfo, heaps)
                : { matchedEntityId: null, matchedEntityName: null, coordAddr: 0, x: null, y: null, z: null };
              const decoded = match.coordAddr > 0 ? __tsReadSchema(match.coordAddr, heaps) : {};

              let score = 0;
              if (match.matchedEntityId) score += 1000;
              if (match.coordAddr > 0) score += 450;
              if (nearOff !== null) score += 300;
              if (decoded.name) score += 200;
              if (typeof decoded.hp === 'number') score += 150;
              if (isPtr) score += 100;
              if (slotAddr < 4 * 1024 * 1024) score += 40; // likely static/global slot

              scored.push({
                score: score,
                slotByteAddress: slotAddr,
                slotHex: __tsFormatHex(slotAddr),
                currentValue: curV,
                currentHex: __tsFormatHex(curV),
                isPointerLike: isPtr,
                nearPlayerOffsetBytes: nearOff,
                matchedEntityId: match.matchedEntityId,
                matchedEntityName: match.matchedEntityName,
                resolvedCoordAddress: match.coordAddr,
                resolvedX: match.x !== null ? Number(match.x.toFixed(3)) : null,
                resolvedY: match.y !== null ? Number(match.y.toFixed(3)) : null,
                resolvedZ: match.z !== null ? Number(match.z.toFixed(3)) : null,
                previewName: decoded.name || null,
                previewHp: (typeof decoded.hp === 'number') ? decoded.hp : null,
                previewMaxHp: (typeof decoded.maxHp === 'number') ? decoded.maxHp : null,
                previewAggroRaw: (typeof decoded.aggro === 'number') ? decoded.aggro : null
              });
            }

            scored.sort(function(a, b) {
              if (b.score !== a.score) return b.score - a.score;
              return a.slotByteAddress - b.slotByteAddress;
            });

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __tsStatus('COMPLETED', 'Target Filter (' + MODE + '): ' + nextCount + ' left (' + durationMs + 'ms)');
            __tsEmit('WASM', {
              stage: 'WASM_TARGET_SCAN_RESULT',
              action: MODE,
              round: __tsState.round,
              durationMs: durationMs,
              totalCandidates: nextCount,
              candidates: scored.slice(0, 32)
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Target filter error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Locks onto a discovered selected-target memory slot and starts a strictly read-only 200ms
     * live watcher that decodes the currently targeted mob's address, coordinates, Name, HP, and Aggro status.
     */
    fun buildLockTargetSlotScript(
        slotByteAddress: Long,
        playerByteAddress: Long
    ): String {
        val safeSlot = slotByteAddress.coerceAtLeast(0L)
        val safePlayer = playerByteAddress.coerceAtLeast(0L)
        return """
        (function() {
          try {
            $TARGET_HEAP_HELPERS
            if (__tsState.pollId !== null) {
              clearInterval(__tsState.pollId);
              __tsState.pollId = null;
            }
            __tsState.lockedSlotAddr = $safeSlot;
            __tsState.tick = 0;

            const pushTargetTick = function() {
              try {
                const heaps = __tsResolveHeaps();
                if (!heaps) return;
                const slotAddr = __tsState.lockedSlotAddr >>> 0;
                const slotIdx = slotAddr >>> 2;
                if (slotAddr === 0 || (slotAddr & 3) !== 0 || slotIdx >= heaps.heapU32.length) return;

                const curVal = heaps.heapU32[slotIdx] >>> 0;
                const hasTarget = (curVal !== 0 && curVal !== 0xFFFFFFFF);
                const playerInfo = __tsResolveLivePlayer($safePlayer, heaps);
                const match = hasTarget
                  ? __tsMatchEntityAndCoords(curVal, playerInfo, heaps)
                  : { matchedEntityId: null, matchedEntityName: null, coordAddr: 0, x: null, y: null, z: null };

                const anchorAddr = match.coordAddr > 0
                  ? match.coordAddr
                  : ((curVal >= 4096 && curVal < heaps.byteLength && (curVal & 3) === 0) ? curVal : 0);

                const decoded = (hasTarget && anchorAddr > 0) ? __tsReadSchema(anchorAddr, heaps) : {};
                __tsState.lastResolvedAddr = hasTarget ? curVal : 0;
                __tsState.lastResolvedCoordAddr = hasTarget ? anchorAddr : 0;
                __tsState.tick++;

                let dist = null;
                if (match.x !== null && playerInfo.hasCoords) {
                  const dx = match.x - playerInfo.x;
                  const dy = match.y - playerInfo.y;
                  const dz = match.z - playerInfo.z;
                  dist = Number(Math.sqrt(dx * dx + dy * dy + dz * dz).toFixed(2));
                }

                const aggroRaw = (typeof decoded.aggro === 'number') ? decoded.aggro : null;
                const payload = {
                  stage: 'WASM_TARGET_WATCH_TICK',
                  tick: __tsState.tick,
                  slotByteAddress: slotAddr,
                  slotHex: __tsFormatHex(slotAddr),
                  targetStructAddress: hasTarget ? curVal : 0,
                  targetCoordAddress: hasTarget ? anchorAddr : 0,
                  hasTarget: hasTarget,
                  matchedEntityId: match.matchedEntityId,
                  resolvedName: decoded.name || null,
                  hp: (typeof decoded.hp === 'number') ? decoded.hp : null,
                  maxHp: (typeof decoded.maxHp === 'number') ? decoded.maxHp : null,
                  aggroRawValue: aggroRaw,
                  isAggro: (aggroRaw !== null) ? (aggroRaw !== 0) : null,
                  worldX: match.x !== null ? Number(match.x.toFixed(3)) : null,
                  worldY: match.y !== null ? Number(match.y.toFixed(3)) : null,
                  worldZ: match.z !== null ? Number(match.z.toFixed(3)) : null,
                  distanceToPlayer: dist
                };

                if (__tsBridge && typeof __tsBridge.updateWasmTarget === 'function') {
                  __tsBridge.updateWasmTarget(JSON.stringify(payload));
                }
              } catch (_) {}
            };

            pushTargetTick();
            __tsState.pollId = setInterval(pushTargetTick, 200);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Target watch error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Strictly read-only diagnostic helper: registers a discovered target's coordinate address
     * inside the local `window.__wvWasmEntities.tracked` list so 500ms live entity tracking
     * includes it (does NOT modify WASM memory or game state).
     */
    fun buildRegisterTargetInTrackedEntitiesScript(
        coordByteAddress: Long,
        structByteAddress: Long,
        label: String
    ): String {
        val safeCoord = coordByteAddress.coerceAtLeast(0L)
        val safeStruct = structByteAddress.coerceAtLeast(0L)
        val escapedLabel = quoteJsString(label.ifBlank { "Target Mob" })
        return """
        (function() {
          try {
            const coordAddr = $safeCoord;
            if (coordAddr <= 0) return;
            const entState = (window.__wvWasmEntities = window.__wvWasmEntities || {
              scanSeq: 0, pollId: null, tick: 0, playerByteAddr: 0, structOffset: 0, tracked: []
            });
            if (!Array.isArray(entState.tracked)) entState.tracked = [];
            const exists = entState.tracked.some(function(t) { return t.byteAddr === coordAddr; });
            if (!exists) {
              const hexCoord = '0x' + (coordAddr >>> 0).toString(16).toUpperCase().padStart(8, '0');
              const structAddr = $safeStruct > 0 ? $safeStruct : coordAddr;
              const hexStruct = '0x' + (structAddr >>> 0).toString(16).toUpperCase().padStart(8, '0');
              entState.tracked.unshift({
                name: $escapedLabel,
                id: hexStruct,
                path: 'HEAPF32[' + (coordAddr >>> 2) + '] @ ' + hexCoord,
                heapIndex: (coordAddr >>> 2),
                byteAddr: coordAddr,
                structBase: structAddr
              });
            }
          } catch (_) {}
        })();
        """.trimIndent()
    }

    fun buildStopTargetWatchScript(): String = """
        (function() {
          try {
            if (window.__wvTargetScan && window.__wvTargetScan.pollId !== null) {
              clearInterval(window.__wvTargetScan.pollId);
              window.__wvTargetScan.pollId = null;
            }
          } catch (_) {}
        })();
    """.trimIndent()

    fun buildResetTargetScanScript(): String = """
        (function() {
          try {
            if (window.__wvTargetScan) {
              window.__wvTargetScan.scanSeq++;
              if (window.__wvTargetScan.pollId !== null) {
                clearInterval(window.__wvTargetScan.pollId);
                window.__wvTargetScan.pollId = null;
              }
              window.__wvTargetScan.round = 0;
              window.__wvTargetScan.slotIndices = null;
              window.__wvTargetScan.prevValues = null;
              window.__wvTargetScan.prevCount = 0;
              window.__wvTargetScan.lockedSlotAddr = 0;
              window.__wvTargetScan.lastResolvedAddr = 0;
              window.__wvTargetScan.lastResolvedCoordAddr = 0;
            }
          } catch (_) {}
        })();
    """.trimIndent()

    private fun quoteJsString(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }
}
