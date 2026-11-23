package com.example

/**
 * Strictly read-only WebAssembly Pointer Live Watcher (200ms), Post-Reload Auto-Verifier,
 * and Step 3 WASM Nearby-Entity Struct Scanner.
 *
 * Security & observational guarantees:
 * - Never writes to WebAssembly linear memory (`HEAPF32`, `HEAPU32`, `HEAP32`).
 * - Never dispatches synthetic touch, pointer, or keyboard events.
 * - Uses non-blocking 1 MB async slices (`setTimeout(resolve, 12)`) during full-heap entity scans
 *   so the browser render loop and WebSocket traffic never stall.
 */
object WasmPointerAndEntityScripts {

    private const val SHARED_HEAP_RESOLVER = """
        const __peBridge = window.TelemetryBridge;
        const __peResolveHeaps = function() {
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
                  label: prefix + 'wasmMemory Float32Array',
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

        const __peEmit = function(cat, obj) {
          try {
            if (__peBridge && typeof __peBridge.logTelemetry === 'function') {
              __peBridge.logTelemetry(cat, JSON.stringify(obj));
            }
          } catch (_) {}
        };

        const __peStatus = function(status, detail) {
          try {
            if (__peBridge && typeof __peBridge.updateScanStatus === 'function') {
              __peBridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __peYield = function(ms) {
          return new Promise(function(resolve) { setTimeout(resolve, ms || 12); });
        };

        const __peFormatHex = function(n) {
          return '0x' + (n >>> 0).toString(16).toUpperCase().padStart(8, '0');
        };

        const __peReadUtf8 = function(u8, startAddr, maxLen) {
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

        const __peReadSchemaField = function(coordAddr, desc, heaps) {
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
          if (vt === 'INLINE_UTF8') {
            return __peReadUtf8(heaps.heapU8, addr, 48);
          }
          if (vt === 'CHAR_PTR') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapU32.length) return null;
            const strPtr = heaps.heapU32[addr >>> 2] >>> 0;
            if (strPtr < 1024 || strPtr >= byteLen) return null;
            return __peReadUtf8(heaps.heapU8, strPtr, 48);
          }
          if (vt === 'STD_STRING') {
            const b0 = heaps.heapU8[addr];
            if ((b0 & 1) === 0) {
              const ssoLen = b0 >>> 1;
              if (ssoLen >= 2 && ssoLen <= 22 && addr + 1 + ssoLen < byteLen) {
                const s1 = __peReadUtf8(heaps.heapU8, addr + 1, ssoLen + 1);
                if (s1) return s1;
              }
            }
            const slots = [8, 0, 4];
            for (let s = 0; s < slots.length; s++) {
              const sa = addr + slots[s];
              if ((sa & 3) !== 0 || (sa >>> 2) >= heaps.heapU32.length) continue;
              const p = heaps.heapU32[sa >>> 2] >>> 0;
              if (p >= 1024 && p < byteLen) {
                const t = __peReadUtf8(heaps.heapU8, p, 48);
                if (t) return t;
              }
            }
            return __peReadUtf8(heaps.heapU8, addr, 48);
          }
          return null;
        };

        const __peReadEntitySchema = function(coordAddr, heaps) {
          const schema = window.__wvStructSchema;
          if (!schema || typeof schema !== 'object' || coordAddr <= 0) return null;
          const out = {};
          let any = false;
          const fields = ['hp', 'maxHp', 'mp', 'maxMp', 'fp', 'maxFp', 'name', 'aggro'];
          for (let i = 0; i < fields.length; i++) {
            const k = fields[i];
            if (schema[k]) {
              const val = __peReadSchemaField(coordAddr, schema[k], heaps);
              if (val !== null && val !== undefined) {
                out[k] = val;
                any = true;
              }
            }
          }
          return any ? out : null;
        };

        const __peDerefChain = function(chain, heaps) {
          const hu32 = heaps.heapU32;
          const hf32 = heaps.heapF32;
          const byteLen = heaps.byteLength;
          const lenU32 = hu32.length;
          const lenF32 = hf32.length;
          let addr = (chain.basePointerAddress >>> 0);
          const offs = Array.isArray(chain.offsets) ? chain.offsets : [];

          for (let step = 0; step < offs.length; step++) {
            const uIdx = addr >>> 2;
            if (addr === 0 || (addr & 3) !== 0 || uIdx >= lenU32) {
              return { status: 'STALE', isVerifiedLive: false, addr: 0, x: 0, y: 0, z: 0 };
            }
            const deref = hu32[uIdx] >>> 0;
            const nextAddr = deref + (offs[step] | 0);
            if (deref === 0 || nextAddr <= 0 || nextAddr >= byteLen || (nextAddr & 3) !== 0) {
              return { status: 'STALE', isVerifiedLive: false, addr: 0, x: 0, y: 0, z: 0 };
            }
            addr = nextAddr;
          }

          const fIdx = addr >>> 2;
          if (fIdx < 0 || fIdx >= lenF32) {
            return { status: 'STALE', isVerifiedLive: false, addr: 0, x: 0, y: 0, z: 0 };
          }
          const rawX = hf32[fIdx];
          const rawY = (fIdx + 1 < lenF32) ? hf32[fIdx + 1] : 0;
          const rawZ = (fIdx + 2 < lenF32) ? hf32[fIdx + 2] : 0;
          if (!Number.isFinite(rawX) || Math.abs(rawX) > 500000 ||
              !Number.isFinite(rawY) || Math.abs(rawY) > 500000 ||
              !Number.isFinite(rawZ) || Math.abs(rawZ) > 500000) {
            return { status: 'STALE', isVerifiedLive: false, addr: 0, x: 0, y: 0, z: 0 };
          }

          const x = Number(rawX.toFixed(4));
          const y = Number(rawY.toFixed(4));
          const z = Number(rawZ.toFixed(4));
          const isZero = Math.abs(x) < 0.00001 && Math.abs(y) < 0.00001 && Math.abs(z) < 0.00001;
          return {
            status: isZero ? 'ZERO' : 'LIVE',
            isVerifiedLive: !isZero,
            addr: addr,
            x: x,
            y: y,
            z: z
          };
        };

        const __ptrWatch = (window.__wvWasmPtrWatch = window.__wvWasmPtrWatch || {
          autoSeq: 0,
          pollId: null,
          tick: 0,
          chains: [],
          hudPointerId: null,
          tracker: {}
        });

        const __peStopPtrWatch = function() {
          try {
            if (__ptrWatch.pollId !== null) {
              clearInterval(__ptrWatch.pollId);
              __ptrWatch.pollId = null;
            }
          } catch (_) {}
        };

        const __peStartPtrWatch = function(inputChains, hudId) {
          __peStopPtrWatch();
          __ptrWatch.chains = Array.isArray(inputChains) ? inputChains : [];
          __ptrWatch.hudPointerId = hudId || null;
          __ptrWatch.tick = 0;
          const prevTracker = __ptrWatch.tracker || {};
          const nextTracker = {};
          for (let i = 0; i < __ptrWatch.chains.length; i++) {
            const c = __ptrWatch.chains[i];
            if (prevTracker[c.id]) {
              nextTracker[c.id] = prevTracker[c.id];
            }
          }
          __ptrWatch.tracker = nextTracker;

          const pushPtrTick = function() {
            try {
              const heaps = __peResolveHeaps();
              if (!heaps || __ptrWatch.chains.length === 0) return;

              __ptrWatch.tick++;
              const results = [];
              let movingCount = 0;
              let liveCount = 0;
              let zeroCount = 0;
              let staleCount = 0;
              let hudResolved = null;

              for (let i = 0; i < __ptrWatch.chains.length; i++) {
                const c = __ptrWatch.chains[i];
                const d = __peDerefChain(c, heaps);
                let tr = __ptrWatch.tracker[c.id];
                let status = d.status;
                let changedFromPrev = false;
                let lastDeltaMag = 0;
                let changeCount = tr ? (tr.changeCount || 0) : 0;

                if (d.status === 'LIVE') {
                  if (!tr || !tr.hasBaseline) {
                    tr = {
                      hasBaseline: true,
                      baseX: d.x,
                      baseY: d.y,
                      baseZ: d.z,
                      prevX: d.x,
                      prevY: d.y,
                      prevZ: d.z,
                      everMoved: false,
                      changeCount: 0
                    };
                    __ptrWatch.tracker[c.id] = tr;
                  } else {
                    const stepDx = d.x - tr.prevX;
                    const stepDy = d.y - tr.prevY;
                    const stepDz = d.z - tr.prevZ;
                    const stepMag = Math.sqrt(stepDx * stepDx + stepDy * stepDy + stepDz * stepDz);
                    if (stepMag >= 0.002) {
                      changedFromPrev = true;
                      lastDeltaMag = Number(stepMag.toFixed(4));
                      tr.changeCount = (tr.changeCount || 0) + 1;
                      tr.everMoved = true;
                    }
                    const totalDx = d.x - tr.baseX;
                    const totalDy = d.y - tr.baseY;
                    const totalDz = d.z - tr.baseZ;
                    const totalMag = Math.sqrt(totalDx * totalDx + totalDy * totalDy + totalDz * totalDz);
                    if (totalMag >= 0.005) {
                      tr.everMoved = true;
                    }
                    tr.prevX = d.x;
                    tr.prevY = d.y;
                    tr.prevZ = d.z;
                    changeCount = tr.changeCount || 0;
                  }
                  if (tr.everMoved) {
                    status = 'MOVING';
                    movingCount++;
                  } else {
                    liveCount++;
                  }
                } else if (d.status === 'ZERO') {
                  zeroCount++;
                } else {
                  staleCount++;
                }

                const entry = {
                  id: c.id,
                  status: status,
                  isVerifiedLive: (status === 'MOVING' || status === 'LIVE'),
                  resolvedTargetAddress: d.addr,
                  resolvedX: d.x,
                  resolvedY: d.y,
                  resolvedZ: d.z,
                  changedFromPrev: changedFromPrev,
                  changeCount: changeCount,
                  lastDeltaMagnitude: lastDeltaMag
                };
                results.push(entry);

                if (__ptrWatch.hudPointerId && c.id === __ptrWatch.hudPointerId && entry.isVerifiedLive) {
                  hudResolved = {
                    id: c.id,
                    expression: c.expression || c.id,
                    addr: d.addr,
                    x: d.x,
                    y: d.y,
                    z: d.z
                  };
                }
              }

              if (hudResolved && __peBridge && typeof __peBridge.updatePlayerCoords === 'function') {
                __peBridge.updatePlayerCoords(
                  'WASM_PTR: ' + hudResolved.expression,
                  hudResolved.x,
                  hudResolved.y,
                  hudResolved.z
                );
              }

              let vitalsAnchorAddr = hudResolved ? hudResolved.addr : 0;
              if (vitalsAnchorAddr <= 0) {
                for (let ri = 0; ri < results.length; ri++) {
                  if (results[ri].isVerifiedLive && results[ri].resolvedTargetAddress > 0) {
                    vitalsAnchorAddr = results[ri].resolvedTargetAddress;
                    break;
                  }
                }
              }
              const playerVitals = vitalsAnchorAddr > 0
                ? __peReadEntitySchema(vitalsAnchorAddr, heaps)
                : null;

              const payload = {
                stage: 'WASM_POINTER_WATCH_TICK',
                tick: __ptrWatch.tick,
                heapSourceLabel: heaps.label,
                heapByteLength: heaps.byteLength,
                movingCount: movingCount,
                liveCount: liveCount,
                zeroCount: zeroCount,
                staleCount: staleCount,
                activeHudPointerId: __ptrWatch.hudPointerId,
                hudTargetAddress: hudResolved ? hudResolved.addr : 0,
                playerVitals: playerVitals,
                results: results
              };

              if (__peBridge && typeof __peBridge.updateWasmPointers === 'function') {
                __peBridge.updateWasmPointers(JSON.stringify(payload));
              }
            } catch (_) {}
          };

          pushPtrTick();
          __ptrWatch.pollId = setInterval(pushPtrTick, 200);
        };
    """

    /**
     * Starts continuous read-only 200ms live pointer verification across all provided chains,
     * streaming `MOVING / LIVE / ZERO / STALE` classifications and optional HUD coordinates.
     */
    fun buildStartPointerWatchScript(
        chainsJsonArray: String,
        hudPointerId: String? = null
    ): String {
        val hudLiteral = if (hudPointerId.isNullOrBlank()) "null" else quoteJsString(hudPointerId)
        return """
        (function() {
          try {
            $SHARED_HEAP_RESOLVER
            const heaps = __peResolveHeaps();
            if (!heaps) {
              __peEmit('WASM', { stage: 'WASM_POINTER_ERROR', message: 'WASM heap unavailable for live pointer watch.' });
              return;
            }
            const chains = $chainsJsonArray;
            if (!Array.isArray(chains) || chains.length === 0) {
              __peStopPtrWatch();
              return;
            }
            __peStartPtrWatch(chains, $hudLiteral);
            __peStatus('COMPLETED', 'Watching ' + chains.length + ' pointer chains live (200ms)');
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Start Pointer Watch error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Updates which pointer chain (if any) streams directly to the top live coordinates HUD
     * without resetting existing movement counters.
     */
    fun buildSetHudPointerScript(hudPointerId: String?): String {
        val hudLiteral = if (hudPointerId.isNullOrBlank()) "null" else quoteJsString(hudPointerId)
        return """
        (function() {
          try {
            if (window.__wvWasmPtrWatch) {
              window.__wvWasmPtrWatch.hudPointerId = $hudLiteral;
            }
          } catch (_) {}
        })();
        """.trimIndent()
    }

    /**
     * Cleanly stops the 200ms live pointer watch interval.
     */
    fun buildStopPointerWatchScript(): String = """
        (function() {
          try {
            if (window.__wvWasmPtrWatch) {
              window.__wvWasmPtrWatch.autoSeq++;
              if (window.__wvWasmPtrWatch.pollId !== null) {
                clearInterval(window.__wvWasmPtrWatch.pollId);
                window.__wvWasmPtrWatch.pollId = null;
              }
            }
            if (window.TelemetryBridge && typeof window.TelemetryBridge.updateScanStatus === 'function') {
              window.TelemetryBridge.updateScanStatus('IDLE', 'Live pointer watch paused');
            }
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Non-blocking post-reload auto-verifier: gently waits for the WASM heap to finish booting
     * (polling every 800ms up to [maxWaitMs]), verifies saved per-site pointer chains, and
     * immediately starts 200ms Live Watch once at least one chain resolves non-zero.
     */
    fun buildAutoVerifySavedPointersScript(
        chainsJsonArray: String,
        hudPointerId: String? = null,
        maxWaitMs: Int = 20_000
    ): String {
        val hudLiteral = if (hudPointerId.isNullOrBlank()) "null" else quoteJsString(hudPointerId)
        val safeMaxWait = maxWaitMs.coerceIn(2_000, 30_000)
        return """
        (async function() {
          try {
            $SHARED_HEAP_RESOLVER
            const chains = $chainsJsonArray;
            if (!Array.isArray(chains) || chains.length === 0) return;

            const myAutoSeq = ++__ptrWatch.autoSeq;
            const deadline = performance.now() + $safeMaxWait;
            let resolvedHeaps = null;
            let bestSnapshot = null;

            while (performance.now() <= deadline) {
              if (myAutoSeq !== __ptrWatch.autoSeq) return;
              const heaps = __peResolveHeaps();
              if (heaps && heaps.byteLength >= 4096) {
                resolvedHeaps = heaps;
                let nonZeroCount = 0;
                let zeroCount = 0;
                const snapshot = [];
                for (let i = 0; i < chains.length; i++) {
                  const d = __peDerefChain(chains[i], heaps);
                  if (d.status === 'LIVE') nonZeroCount++;
                  else if (d.status === 'ZERO') zeroCount++;
                  snapshot.push({
                    id: chains[i].id,
                    status: d.status,
                    isVerifiedLive: d.isVerifiedLive,
                    resolvedTargetAddress: d.addr,
                    resolvedX: d.x,
                    resolvedY: d.y,
                    resolvedZ: d.z
                  });
                }
                bestSnapshot = { nonZeroCount: nonZeroCount, zeroCount: zeroCount, results: snapshot };
                if (nonZeroCount > 0) break;
              }
              await __peYield(800);
            }

            if (myAutoSeq !== __ptrWatch.autoSeq) return;

            if (!resolvedHeaps || !bestSnapshot) {
              __peEmit('WASM', {
                stage: 'WASM_POINTER_AUTO_VERIFY_DONE',
                heapFound: false,
                nonZeroCount: 0,
                zeroCount: 0,
                totalCount: chains.length,
                results: []
              });
              return;
            }

            let autoHudId = $hudLiteral;
            if (!autoHudId) {
              for (let i = 0; i < bestSnapshot.results.length; i++) {
                if (bestSnapshot.results[i].status === 'LIVE') {
                  autoHudId = bestSnapshot.results[i].id;
                  break;
                }
              }
            }

            __peEmit('WASM', {
              stage: 'WASM_POINTER_AUTO_VERIFY_DONE',
              heapFound: true,
              heapSourceLabel: resolvedHeaps.label,
              heapByteLength: resolvedHeaps.byteLength,
              nonZeroCount: bestSnapshot.nonZeroCount,
              zeroCount: bestSnapshot.zeroCount,
              totalCount: chains.length,
              autoHudPointerId: autoHudId,
              results: bestSnapshot.results
            });

            __peStartPtrWatch(chains, autoHudId);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Auto-verify saved pointers error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 3 (WASM Mode): Read-only, non-blocking 1 MB sliced scanner over `window.HEAPF32`
     * that finds nearby entity `(X, Y, Z)` coordinate structs within `[minRadius, maxRadius]`
     * of the player's live WASM coordinate address, deduplicates mirrored matrix/physics copies,
     * reads struct metadata if a coordinate offset is known, and stores discovered entity
     * indices for optional 500ms live entity tracking.
     */
    fun buildScanWasmNearbyEntitiesScript(
        playerByteAddress: Long,
        playerX: Double,
        playerY: Double,
        playerZ: Double,
        structCoordOffset: Int = 0,
        minRadius: Double = 1.5,
        maxRadius: Double = 250.0,
        maxYDelta: Double = 60.0
    ): String {
        val safePlayerAddr = playerByteAddress.coerceAtLeast(0L)
        val safePx = if (playerX.isFinite()) playerX.toString() else "0.0"
        val safePy = if (playerY.isFinite()) playerY.toString() else "0.0"
        val safePz = if (playerZ.isFinite()) playerZ.toString() else "0.0"
        val safeStructOff = structCoordOffset.coerceIn(0, 1024)
        val safeMinR = minRadius.coerceIn(0.5, 50.0)
        val safeMaxR = maxRadius.coerceIn(10.0, 2000.0)
        val safeMaxY = maxYDelta.coerceIn(5.0, 500.0)

        return """
        (async function() {
          try {
            $SHARED_HEAP_RESOLVER
            const __entState = (window.__wvWasmEntities = window.__wvWasmEntities || {
              scanSeq: 0,
              pollId: null,
              tick: 0,
              playerByteAddr: 0,
              structOffset: 0,
              tracked: []
            });

            const heaps = __peResolveHeaps();
            if (!heaps) {
              __peStatus('ERROR', 'WASM heap unavailable for entity scan');
              __peEmit('ENTITY', {
                stage: 'WASM_ENTITIES_DISCOVERED',
                error: 'Could not locate window.HEAPF32 on this page.',
                entities: []
              });
              return;
            }

            const mySeq = ++__entState.scanSeq;
            const startMs = performance.now();
            const hf32 = heaps.heapF32;
            const h32 = heaps.heap32;
            const lenF32 = hf32.length;

            // Refresh live player address & coordinates if active HUD pointer is running
            let playerAddr = $safePlayerAddr;
            let px = Number($safePx);
            let py = Number($safePy);
            let pz = Number($safePz);
            if (__ptrWatch && __ptrWatch.hudPointerId && Array.isArray(__ptrWatch.chains)) {
              for (let i = 0; i < __ptrWatch.chains.length; i++) {
                if (__ptrWatch.chains[i].id === __ptrWatch.hudPointerId) {
                  const d = __peDerefChain(__ptrWatch.chains[i], heaps);
                  if (d.isVerifiedLive && d.addr > 0) {
                    playerAddr = d.addr;
                    px = d.x;
                    py = d.y;
                    pz = d.z;
                  }
                  break;
                }
              }
            } else if (playerAddr > 0 && ((playerAddr >>> 2) + 2) < lenF32) {
              const pIdx = playerAddr >>> 2;
              if (Number.isFinite(hf32[pIdx]) && Number.isFinite(hf32[pIdx + 1]) && Number.isFinite(hf32[pIdx + 2])) {
                px = hf32[pIdx];
                py = hf32[pIdx + 1];
                pz = hf32[pIdx + 2];
              }
            }

            const playerFloatIdx = playerAddr >>> 2;
            const STRUCT_OFF = $safeStructOff;
            const MIN_R = Number($safeMinR);
            const MAX_R = Number($safeMaxR);
            const MIN_R2 = MIN_R * MIN_R;
            const MAX_R2 = MAX_R * MAX_R;
            const MAX_Y = Number($safeMaxY);
            const START_IDX = lenF32 > 4096 ? 256 : 0;
            const CHUNK_FLOATS = 262144; // 1 MB per non-blocking slice
            const MAX_RAW_HITS = 2000;

            __peStatus('RUNNING', 'Step 3 WASM: scanning HEAPF32 for nearby entities…');

            const rawHits = [];
            for (let base = START_IDX; base < lenF32 - 2 && rawHits.length < MAX_RAW_HITS; base += CHUNK_FLOATS) {
              if (mySeq !== __entState.scanSeq) return;
              const end = Math.min(base + CHUNK_FLOATS, lenF32 - 2);
              for (let i = base; i < end && rawHits.length < MAX_RAW_HITS; i++) {
                // Skip player's own immediate struct vicinity
                if (playerFloatIdx > 0 && Math.abs(i - playerFloatIdx) <= 16) continue;

                const vx = hf32[i];
                if (vx !== vx) continue;
                const dx = vx - px;
                if (dx < -MAX_R || dx > MAX_R) continue;

                const vy = hf32[i + 1];
                if (vy !== vy) continue;
                const dy = vy - py;
                if (dy < -MAX_Y || dy > MAX_Y) continue;

                const vz = hf32[i + 2];
                if (vz !== vz) continue;
                const dz = vz - pz;
                if (dz < -MAX_R || dz > MAX_R) continue;

                const dist2 = dx * dx + dy * dy + dz * dz;
                if (dist2 < MIN_R2 || dist2 > MAX_R2) continue;

                // Reject degenerate zero-plane or NaN-like values
                if (Math.abs(vx) < 0.001 && Math.abs(vz) < 0.001) continue;

                const byteAddr = i * 4;
                const structBase = (STRUCT_OFF > 0 && byteAddr >= STRUCT_OFF) ? (byteAddr - STRUCT_OFF) : byteAddr;
                const matchesOffsetMod = (STRUCT_OFF > 0 && ((byteAddr - STRUCT_OFF) & 15) === 0);
                rawHits.push({
                  heapIndex: i,
                  byteAddr: byteAddr,
                  structBase: structBase,
                  matchesOffsetMod: matchesOffsetMod,
                  x: vx,
                  y: vy,
                  z: vz,
                  dx: dx,
                  dy: dy,
                  dz: dz,
                  dist: Math.sqrt(dist2)
                });
                // Advance past this Vec3 so overlapping (Y, Z, next) slots aren't double-counted
                i += 2;
              }
              if (end < lenF32 - 2 && rawHits.length < MAX_RAW_HITS) {
                await __peYield(12);
              }
            }

            if (mySeq !== __entState.scanSeq) return;

            // Sort by distance ascending, preferring aligned struct matches when equidistant
            rawHits.sort(function(a, b) {
              if (Math.abs(a.dist - b.dist) > 0.05) return a.dist - b.dist;
              if (a.matchesOffsetMod !== b.matchesOffsetMod) return a.matchesOffsetMod ? -1 : 1;
              return a.heapIndex - b.heapIndex;
            });

            // Spatial deduplication: keep distinct entity positions (collapse render/physics clones within 0.25u)
            const deduped = [];
            for (let i = 0; i < rawHits.length && deduped.length < 40; i++) {
              const cand = rawHits[i];
              let isClone = false;
              for (let j = 0; j < deduped.length; j++) {
                const ex = deduped[j];
                const ddx = cand.x - ex.x;
                const ddy = cand.y - ex.y;
                const ddz = cand.z - ex.z;
                if ((ddx * ddx + ddy * ddy + ddz * ddz) < 0.0625) {
                  isClone = true;
                  break;
                }
              }
              if (!isClone) {
                deduped.push(cand);
              }
            }

            const vw = window.innerWidth || 0;
            const vh = window.innerHeight || 0;
            const entities = [];
            const tracked = [];

            for (let i = 0; i < deduped.length; i++) {
              const e = deduped[i];
              const baseIdx = e.structBase >>> 2;
              let candidateId = '';
              if (STRUCT_OFF > 0 && baseIdx >= 0 && baseIdx + 2 < h32.length) {
                const rawId = h32[baseIdx] >>> 0;
                if (rawId > 0 && rawId < 100000000) {
                  candidateId = String(rawId);
                }
              }
              const hexCoord = __peFormatHex(e.byteAddr);
              const hexStruct = __peFormatHex(e.structBase);
              const relTag = 'Δ(' + e.dx.toFixed(1) + ', ' + e.dy.toFixed(1) + ', ' + e.dz.toFixed(1) + ')';
              const entityName = 'WASM Entity #' + (i + 1);
              const entityPath = 'HEAPF32[' + e.heapIndex + '] @ ' + hexCoord;
              const decoded = __peReadEntitySchema(e.byteAddr, heaps) || {};
              const activeTargetCoord = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedCoordAddr > 0)
                ? window.__wvTargetScan.lastResolvedCoordAddr : 0;
              const activeTargetStruct = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedAddr > 0)
                ? window.__wvTargetScan.lastResolvedAddr : 0;
              const isActiveTgt = (activeTargetCoord > 0 && Math.abs(e.byteAddr - activeTargetCoord) <= 64) ||
                (activeTargetStruct > 0 && (e.structBase === activeTargetStruct || Math.abs(e.byteAddr - activeTargetStruct) <= 1024));
              const aggroRaw = (typeof decoded.aggro === 'number') ? decoded.aggro : null;

              entities.push({
                name: entityName,
                id: candidateId || hexStruct,
                path: entityPath,
                coordByteAddress: e.byteAddr,
                heapIndex: e.heapIndex,
                resolvedName: decoded.name || null,
                hp: (typeof decoded.hp === 'number') ? decoded.hp : null,
                maxHp: (typeof decoded.maxHp === 'number') ? decoded.maxHp : null,
                mp: (typeof decoded.mp === 'number') ? decoded.mp : null,
                maxMp: (typeof decoded.maxMp === 'number') ? decoded.maxMp : null,
                fp: (typeof decoded.fp === 'number') ? decoded.fp : null,
                maxFp: (typeof decoded.maxFp === 'number') ? decoded.maxFp : null,
                aggroRawValue: aggroRaw,
                isAggro: (aggroRaw !== null) ? (aggroRaw !== 0) : null,
                isActiveGameTarget: isActiveTgt,
                worldX: Number(e.x.toFixed(3)),
                worldY: Number(e.y.toFixed(3)),
                worldZ: Number(e.z.toFixed(3)),
                distanceToPlayer: Number(e.dist.toFixed(2)),
                screenX: 0,
                screenY: 0,
                inViewport: e.dist <= 65.0,
                sampleKeys: [
                  'WASM_STRUCT',
                  'struct=' + hexStruct,
                  relTag,
                  STRUCT_OFF > 0 ? ('off=+0x' + STRUCT_OFF.toString(16).toUpperCase()) : 'spatial-vec3'
                ]
              });
              tracked.push({
                name: entityName,
                id: candidateId || hexStruct,
                path: entityPath,
                heapIndex: e.heapIndex,
                byteAddr: e.byteAddr,
                structBase: e.structBase
              });
            }

            __entState.playerByteAddr = playerAddr;
            __entState.structOffset = STRUCT_OFF;
            __entState.tracked = tracked;

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __peStatus(
              entities.length > 0 ? 'COMPLETED' : 'IDLE',
              'Step 3 WASM: ' + entities.length + ' nearby entities (' + durationMs + 'ms)'
            );
            __peEmit('ENTITY', {
              stage: 'WASM_ENTITIES_DISCOVERED',
              durationMs: durationMs,
              heapSourceLabel: heaps.label,
              playerByteAddress: playerAddr,
              playerCoords: { x: Number(px.toFixed(3)), y: Number(py.toFixed(3)), z: Number(pz.toFixed(3)) },
              structCoordOffset: STRUCT_OFF,
              rawCandidateCount: rawHits.length,
              viewportWidth: vw,
              viewportHeight: vh,
              projectionMode: 'WASM Spatial HEAPF32 (radius ' + MIN_R + '..' + MAX_R + 'u)',
              arrayPath: heaps.label + ' near ' + __peFormatHex(playerAddr),
              entities: entities
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'WASM entity scan failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Entity Scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Starts a read-only 500ms live position refresh loop for the discovered WASM nearby entities,
     * recomputing world `(X, Y, Z)` and distance to the player's live dereferenced coordinates.
     */
    fun buildStartWasmEntitiesLiveRefreshScript(): String = """
        (function() {
          try {
            $SHARED_HEAP_RESOLVER
            const __entState = window.__wvWasmEntities;
            if (!__entState || !Array.isArray(__entState.tracked) || __entState.tracked.length === 0) {
              return;
            }
            if (__entState.pollId !== null) {
              clearInterval(__entState.pollId);
              __entState.pollId = null;
            }
            __entState.tick = 0;

            const pushEntityTick = function() {
              try {
                const heaps = __peResolveHeaps();
                if (!heaps) return;
                const hf32 = heaps.heapF32;
                const lenF32 = hf32.length;

                let px = 0, py = 0, pz = 0;
                let hasPlayer = false;
                if (__ptrWatch && __ptrWatch.hudPointerId && Array.isArray(__ptrWatch.chains)) {
                  for (let i = 0; i < __ptrWatch.chains.length; i++) {
                    if (__ptrWatch.chains[i].id === __ptrWatch.hudPointerId) {
                      const d = __peDerefChain(__ptrWatch.chains[i], heaps);
                      if (d.isVerifiedLive) {
                        px = d.x; py = d.y; pz = d.z;
                        hasPlayer = true;
                      }
                      break;
                    }
                  }
                }
                if (!hasPlayer && __entState.playerByteAddr > 0) {
                  const pIdx = __entState.playerByteAddr >>> 2;
                  if (pIdx + 2 < lenF32 && Number.isFinite(hf32[pIdx])) {
                    px = hf32[pIdx];
                    py = hf32[pIdx + 1];
                    pz = hf32[pIdx + 2];
                    hasPlayer = true;
                  }
                }

                __entState.tick++;
                const out = [];
                for (let i = 0; i < __entState.tracked.length; i++) {
                  const t = __entState.tracked[i];
                  const idx = t.heapIndex | 0;
                  if (idx < 0 || idx + 2 >= lenF32) continue;
                  const x = hf32[idx];
                  const y = hf32[idx + 1];
                  const z = hf32[idx + 2];
                  if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) continue;
                  if (Math.abs(x) > 500000 || Math.abs(y) > 500000 || Math.abs(z) > 500000) continue;
                  const dx = hasPlayer ? (x - px) : 0;
                  const dy = hasPlayer ? (y - py) : 0;
                  const dz = hasPlayer ? (z - pz) : 0;
                  const dist = hasPlayer ? Math.sqrt(dx * dx + dy * dy + dz * dz) : 0;
                  const decoded = __peReadEntitySchema(t.byteAddr, heaps) || {};
                  const activeTargetCoord = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedCoordAddr > 0)
                    ? window.__wvTargetScan.lastResolvedCoordAddr : 0;
                  const activeTargetStruct = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedAddr > 0)
                    ? window.__wvTargetScan.lastResolvedAddr : 0;
                  const isActiveTgt = (activeTargetCoord > 0 && Math.abs(t.byteAddr - activeTargetCoord) <= 64) ||
                    (activeTargetStruct > 0 && (t.structBase === activeTargetStruct || Math.abs(t.byteAddr - activeTargetStruct) <= 1024));
                  const aggroRaw = (typeof decoded.aggro === 'number') ? decoded.aggro : null;
                  out.push({
                    name: t.name,
                    id: t.id,
                    path: t.path,
                    coordByteAddress: t.byteAddr,
                    heapIndex: t.heapIndex,
                    resolvedName: decoded.name || null,
                    hp: (typeof decoded.hp === 'number') ? decoded.hp : null,
                    maxHp: (typeof decoded.maxHp === 'number') ? decoded.maxHp : null,
                    mp: (typeof decoded.mp === 'number') ? decoded.mp : null,
                    maxMp: (typeof decoded.maxMp === 'number') ? decoded.maxMp : null,
                    fp: (typeof decoded.fp === 'number') ? decoded.fp : null,
                    maxFp: (typeof decoded.maxFp === 'number') ? decoded.maxFp : null,
                    aggroRawValue: aggroRaw,
                    isAggro: (aggroRaw !== null) ? (aggroRaw !== 0) : null,
                    isActiveGameTarget: isActiveTgt,
                    worldX: Number(x.toFixed(3)),
                    worldY: Number(y.toFixed(3)),
                    worldZ: Number(z.toFixed(3)),
                    distanceToPlayer: Number(dist.toFixed(2)),
                    screenX: 0,
                    screenY: 0,
                    inViewport: dist <= 65.0,
                    sampleKeys: [
                      'WASM_STRUCT',
                      'struct=' + __peFormatHex(t.structBase),
                      'Δ(' + dx.toFixed(1) + ', ' + dy.toFixed(1) + ', ' + dz.toFixed(1) + ')'
                    ]
                  });
                }

                out.sort(function(a, b) { return a.distanceToPlayer - b.distanceToPlayer; });

                if (__peBridge && typeof __peBridge.updateWasmEntities === 'function') {
                  __peBridge.updateWasmEntities(JSON.stringify({
                    stage: 'WASM_ENTITIES_TICK',
                    tick: __entState.tick,
                    entities: out
                  }));
                }
              } catch (_) {}
            };

            pushEntityTick();
            __entState.pollId = setInterval(pushEntityTick, 500);
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Cleanly stops the 500ms live WASM entity position refresh loop.
     */
    fun buildStopWasmEntitiesLiveRefreshScript(): String = """
        (function() {
          try {
            if (window.__wvWasmEntities && window.__wvWasmEntities.pollId !== null) {
              clearInterval(window.__wvWasmEntities.pollId);
              window.__wvWasmEntities.pollId = null;
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
