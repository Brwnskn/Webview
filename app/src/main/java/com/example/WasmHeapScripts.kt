package com.example

/**
 * Targeted WebAssembly Heap Value-Tracker JavaScript routines.
 *
 * Strictly observational and read-only:
 * - Never writes to WebAssembly linear memory.
 * - Uses chunked async yields (`await new Promise(r => setTimeout(r, 12))`) across large
 *   `HEAP32` / `HEAPF32` views so the browser render thread and WebSocket loop never freeze.
 * - Supports initial exact-value scanning (e.g. HP = 4338), instant delta filtering after
 *   in-game HP changes, and live 200 ms inspection of neighboring floats (±4 to ±16 bytes)
 *   around the isolated memory address.
 */
object WasmHeapScripts {

    private const val WASM_RUNTIME = """
        const __bridge = window.TelemetryBridge;
        const __wasm = (window.__wvWasmTracker = window.__wvWasmTracker || {
          scanSeq: 0,
          sourceLabel: '',
          heapByteLength: 0,
          candidates32: [],
          candidatesF32: [],
          totalMatches32: 0,
          totalMatchesF32: 0,
          filterRound: 0,
          isolated: null,
          pollId: null,
          pollTick: 0
        });

        const __emitWasm = function(obj) {
          try {
            if (__bridge && typeof __bridge.logTelemetry === 'function') {
              __bridge.logTelemetry('WASM', JSON.stringify(obj));
            }
          } catch (_) {}
        };

        const __setWasmStatus = function(status, detail) {
          try {
            if (__bridge && typeof __bridge.updateScanStatus === 'function') {
              __bridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __yieldWasmChunk = function() {
          return new Promise(function(resolve) {
            setTimeout(resolve, 12);
          });
        };

        /**
         * Safely resolves live Int32Array (HEAP32) and Float32Array (HEAPF32) views over the
         * page's WebAssembly linear memory, checking window globals first and Emscripten Module
         * containers as automatic fallback. Handles detached buffers after memory.grow().
         */
        const __resolveWasmHeaps = function() {
          let h32 = null;
          let hf32 = null;
          let label = '';

          const tryContainer = function(obj, prefix) {
            if (!obj || typeof obj !== 'object') return false;
            try {
              const c32 = obj.HEAP32;
              const cf32 = obj.HEAPF32;
              if (c32 instanceof Int32Array && c32.buffer && c32.buffer.byteLength > 0) {
                h32 = c32;
                hf32 = (cf32 instanceof Float32Array && cf32.buffer === c32.buffer)
                  ? cf32
                  : new Float32Array(c32.buffer);
                label = prefix + 'HEAP32 / ' + prefix + 'HEAPF32';
                return true;
              }
              if (cf32 instanceof Float32Array && cf32.buffer && cf32.buffer.byteLength > 0) {
                hf32 = cf32;
                h32 = new Int32Array(cf32.buffer);
                label = prefix + 'HEAPF32 (derived HEAP32)';
                return true;
              }
              const h8 = obj.HEAPU8 || obj.HEAP8;
              if (h8 && ArrayBuffer.isView(h8) && h8.buffer && h8.buffer.byteLength >= 4) {
                h32 = new Int32Array(h8.buffer);
                hf32 = new Float32Array(h8.buffer);
                label = prefix + 'HEAPU8 buffer (Int32/Float32 views)';
                return true;
              }
              const mem = obj.wasmMemory || (obj.asm && obj.asm.memory) || (obj.wasmExports && obj.wasmExports.memory);
              if (typeof WebAssembly !== 'undefined' && mem instanceof WebAssembly.Memory && mem.buffer && mem.buffer.byteLength >= 4) {
                h32 = new Int32Array(mem.buffer);
                hf32 = new Float32Array(mem.buffer);
                label = prefix + 'wasmMemory.buffer';
                return true;
              }
            } catch (_) {}
            return false;
          };

          if (tryContainer(window, 'window.')) {
            return { heap32: h32, heapF32: hf32, label: label, byteLength: h32.buffer.byteLength };
          }
          if (tryContainer(window.Module, 'window.Module.')) {
            return { heap32: h32, heapF32: hf32, label: label, byteLength: h32.buffer.byteLength };
          }
          const fallbackRoots = ['unityInstance', 'game', 'app', 'engine', 'Game', 'App', 'Engine'];
          for (let i = 0; i < fallbackRoots.length; i++) {
            const rk = fallbackRoots[i];
            try {
              const root = window[rk];
              if (!root || typeof root !== 'object') continue;
              if (tryContainer(root, 'window.' + rk + '.')) {
                return { heap32: h32, heapF32: hf32, label: label, byteLength: h32.buffer.byteLength };
              }
              if (root.Module && tryContainer(root.Module, 'window.' + rk + '.Module.')) {
                return { heap32: h32, heapF32: hf32, label: label, byteLength: h32.buffer.byteLength };
              }
            } catch (_) {}
          }
          return null;
        };

        const __stopWasmNeighborPoll = function() {
          try {
            if (__wasm.pollId !== null) {
              clearInterval(__wasm.pollId);
              __wasm.pollId = null;
            }
          } catch (_) {}
        };

        /**
         * Reads neighboring ±4 to ±16 byte slots (offsets -16, -12, -8, -4, 0, +4, +8, +12, +16)
         * around the isolated 32-bit element index from both HEAPF32 and HEAP32, and pushes
         * a live snapshot every 200 ms through TelemetryBridge.updateWasmNeighbors().
         */
        const __startWasmNeighborPoll = function(heapType, targetIndex) {
          __stopWasmNeighborPoll();
          __wasm.isolated = { heapType: heapType, index: targetIndex };
          __wasm.pollTick = 0;

          const pushSnapshot = function() {
            try {
              const heaps = __resolveWasmHeaps();
              if (!heaps) return;
              const h32 = heaps.heap32;
              const hf32 = heaps.heapF32;
              const len = Math.min(h32.length, hf32.length);
              if (targetIndex < 0 || targetIndex >= len) return;

              __wasm.pollTick++;
              const neighbors = [];
              for (let step = -4; step <= 4; step++) {
                const idx = targetIndex + step;
                if (idx < 0 || idx >= len) continue;
                const fv = hf32[idx];
                const iv = h32[idx];
                neighbors.push({
                  byteOffset: step * 4,
                  heapIndex: idx,
                  byteAddress: idx * 4,
                  floatValue: Number.isFinite(fv) ? Number(fv.toFixed(5)) : 0,
                  intValue: iv
                });
              }

              const curVal = heapType === 'HEAPF32'
                ? (Number.isFinite(hf32[targetIndex]) ? hf32[targetIndex].toFixed(3) : 'NaN')
                : String(h32[targetIndex]);

              const payload = {
                stage: 'WASM_NEIGHBORS_TICK',
                heapSourceLabel: heaps.label,
                heapByteLength: heaps.byteLength,
                heapType: heapType,
                isolatedIndex: targetIndex,
                isolatedByteAddress: targetIndex * 4,
                currentValue: curVal,
                tick: __wasm.pollTick,
                neighbors: neighbors
              };

              if (__bridge && typeof __bridge.updateWasmNeighbors === 'function') {
                __bridge.updateWasmNeighbors(JSON.stringify(payload));
              }
            } catch (_) {}
          };

          pushSnapshot();
          __wasm.pollId = setInterval(pushSnapshot, 200);
        };
    """

    /**
     * Step 1: Non-blocking chunked scan across `window.HEAP32` and `window.HEAPF32`
     * (with automatic `window.Module` fallback) to find all memory indices matching
     * the exact entered stat/HP value.
     */
    fun buildInitialScanScript(targetValue: Double): String {
        val valueLiteral = if (targetValue.isFinite()) targetValue.toString() else "0"
        return """
        (async function() {
          try {
            $WASM_RUNTIME
            const targetVal = Number($valueLiteral);
            if (!Number.isFinite(targetVal)) {
              __emitWasm({ stage: 'WASM_HEAP_ERROR', message: 'Enter a valid numeric stat value (e.g. 4338).' });
              return;
            }

            const heaps = __resolveWasmHeaps();
            if (!heaps) {
              __setWasmStatus('ERROR', 'No WASM HEAP32/HEAPF32 found');
              __emitWasm({
                stage: 'WASM_HEAP_ERROR',
                message: 'Could not locate window.HEAP32, window.HEAPF32, or window.Module.HEAP32 on this page.'
              });
              return;
            }

            __stopWasmNeighborPoll();
            const mySeq = ++__wasm.scanSeq;
            const startMs = performance.now();
            const h32 = heaps.heap32;
            const hf32 = heaps.heapF32;
            const len32 = h32.length;
            const lenF32 = hf32.length;
            const isExactInt = Number.isInteger(targetVal) && targetVal >= -2147483648 && targetVal <= 2147483647;
            const targetInt = isExactInt ? (targetVal | 0) : 0;
            const EPS = 0.001;
            const CHUNK_ITEMS = 262144; // 1 MB per async slice
            const MAX_STORED = 120000;

            const matches32 = [];
            const matchesF32 = [];
            let totalCount32 = 0;
            let totalCountF32 = 0;

            __wasm.sourceLabel = heaps.label;
            __wasm.heapByteLength = heaps.byteLength;
            __wasm.filterRound = 0;
            __wasm.isolated = null;

            const totalElements = Math.max(len32, lenF32);
            __setWasmStatus('RUNNING', 'WASM Scan: 0% (' + heaps.label + ')…');

            let lastProgressPct = -1;
            for (let base = 0; base < totalElements; base += CHUNK_ITEMS) {
              if (mySeq !== __wasm.scanSeq) return;
              const end = Math.min(base + CHUNK_ITEMS, totalElements);

              if (isExactInt && base < len32) {
                const end32 = Math.min(end, len32);
                for (let i = base; i < end32; i++) {
                  if (h32[i] === targetInt) {
                    totalCount32++;
                    if (matches32.length < MAX_STORED) matches32.push(i);
                  }
                }
              }

              if (base < lenF32) {
                const endF = Math.min(end, lenF32);
                for (let i = base; i < endF; i++) {
                  const fv = hf32[i];
                  if (fv === targetVal || (Number.isFinite(fv) && Math.abs(fv - targetVal) < EPS)) {
                    totalCountF32++;
                    if (matchesF32.length < MAX_STORED) matchesF32.push(i);
                  }
                }
              }

              const pct = Math.min(99, Math.floor((end / Math.max(1, totalElements)) * 100));
              if (pct >= lastProgressPct + 10) {
                lastProgressPct = pct;
                __setWasmStatus('RUNNING', 'WASM Scan ' + pct + '% (' + (totalCount32 + totalCountF32) + ' matches)…');
              }

              if (end < totalElements) {
                await __yieldWasmChunk();
              }
            }

            if (mySeq !== __wasm.scanSeq) return;

            __wasm.candidates32 = matches32;
            __wasm.candidatesF32 = matchesF32;
            __wasm.totalMatches32 = totalCount32;
            __wasm.totalMatchesF32 = totalCountF32;

            const totalMatches = totalCount32 + totalCountF32;
            const durationMs = Number((performance.now() - startMs).toFixed(1));
            const samples = [];
            for (let i = 0; i < matches32.length && samples.length < 10; i++) {
              const idx = matches32[i];
              samples.push({ heapType: 'HEAP32', index: idx, byteAddress: idx * 4, currentValue: String(h32[idx]) });
            }
            for (let i = 0; i < matchesF32.length && samples.length < 12; i++) {
              const idx = matchesF32[i];
              samples.push({ heapType: 'HEAPF32', index: idx, byteAddress: idx * 4, currentValue: hf32[idx].toFixed(3) });
            }

            let autoIsolated = null;
            if (totalMatches === 1 && samples.length === 1) {
              autoIsolated = samples[0];
              __startWasmNeighborPoll(autoIsolated.heapType, autoIsolated.index);
            }

            __setWasmStatus(
              'COMPLETED',
              'WASM Scan: ' + totalMatches + ' matches (' + durationMs + 'ms)'
            );
            __emitWasm({
              stage: 'WASM_HEAP_SCAN_COMPLETE',
              durationMs: durationMs,
              heapSourceLabel: heaps.label,
              heapByteLength: heaps.byteLength,
              targetValue: targetVal,
              totalMatches: totalMatches,
              heap32MatchCount: totalCount32,
              heapF32MatchCount: totalCountF32,
              cappedStored: (totalCount32 > MAX_STORED) || (totalCountF32 > MAX_STORED),
              samples: samples,
              autoIsolated: autoIsolated
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'WASM scan failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Heap Scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 2: Instant Value-Delta Filter (Change Detection).
     * Narrows down the stored matching indices to only those whose current value in
     * `HEAP32` / `HEAPF32` updated to match the new entered HP/stat value. Automatically
     * starts the live ±4..±16 byte Neighboring Memory Inspector as soon as an address is isolated.
     */
    fun buildFilterDeltaScript(newTargetValue: Double): String {
        val valueLiteral = if (newTargetValue.isFinite()) newTargetValue.toString() else "0"
        return """
        (function() {
          try {
            $WASM_RUNTIME
            const newVal = Number($valueLiteral);
            if (!Number.isFinite(newVal)) {
              __emitWasm({ stage: 'WASM_HEAP_ERROR', message: 'Enter a valid updated HP value before filtering.' });
              return;
            }

            if ((!__wasm.candidates32 || __wasm.candidates32.length === 0) &&
                (!__wasm.candidatesF32 || __wasm.candidatesF32.length === 0)) {
              __emitWasm({
                stage: 'WASM_HEAP_ERROR',
                message: 'Run Step 1 (Scan HEAP32 / F32) with your initial HP value first.'
              });
              return;
            }

            const heaps = __resolveWasmHeaps();
            if (!heaps) {
              __emitWasm({ stage: 'WASM_HEAP_ERROR', message: 'WASM heap is no longer accessible.' });
              return;
            }

            const startMs = performance.now();
            const h32 = heaps.heap32;
            const hf32 = heaps.heapF32;
            const len32 = h32.length;
            const lenF32 = hf32.length;
            const isExactInt = Number.isInteger(newVal) && newVal >= -2147483648 && newVal <= 2147483647;
            const targetInt = isExactInt ? (newVal | 0) : 0;
            const EPS = 0.001;

            const prevTotal = __wasm.candidates32.length + __wasm.candidatesF32.length;
            const next32 = [];
            const nextF32 = [];

            if (isExactInt) {
              for (let i = 0; i < __wasm.candidates32.length; i++) {
                const idx = __wasm.candidates32[i];
                if (idx >= 0 && idx < len32 && h32[idx] === targetInt) {
                  next32.push(idx);
                }
              }
            }

            for (let i = 0; i < __wasm.candidatesF32.length; i++) {
              const idx = __wasm.candidatesF32[i];
              if (idx >= 0 && idx < lenF32) {
                const fv = hf32[idx];
                if (fv === newVal || (Number.isFinite(fv) && Math.abs(fv - newVal) < EPS)) {
                  nextF32.push(idx);
                }
              }
            }

            __wasm.candidates32 = next32;
            __wasm.candidatesF32 = nextF32;
            __wasm.totalMatches32 = next32.length;
            __wasm.totalMatchesF32 = nextF32.length;
            __wasm.filterRound = (__wasm.filterRound || 0) + 1;

            const samples = [];
            for (let i = 0; i < next32.length && samples.length < 12; i++) {
              const idx = next32[i];
              samples.push({ heapType: 'HEAP32', index: idx, byteAddress: idx * 4, currentValue: String(h32[idx]) });
            }
            for (let i = 0; i < nextF32.length && samples.length < 16; i++) {
              const idx = nextF32[i];
              samples.push({ heapType: 'HEAPF32', index: idx, byteAddress: idx * 4, currentValue: hf32[idx].toFixed(3) });
            }

            const remaining = next32.length + nextF32.length;
            const durationMs = Number((performance.now() - startMs).toFixed(2));

            // Automatically start Neighboring Memory Inspector when narrowed to 1 address (or <= 2 identical-offset views)
            let autoIsolated = null;
            if (samples.length === 1) {
              autoIsolated = samples[0];
              __startWasmNeighborPoll(autoIsolated.heapType, autoIsolated.index);
            } else if (remaining > 0 && remaining <= 2 && next32.length === 1 && nextF32.length === 1 && next32[0] === nextF32[0]) {
              autoIsolated = samples[0];
              __startWasmNeighborPoll(autoIsolated.heapType, autoIsolated.index);
            }

            __setWasmStatus(
              remaining > 0 ? 'COMPLETED' : 'IDLE',
              remaining === 1
                ? ('Isolated ' + autoIsolated.heapType + '[' + autoIsolated.index + '] — polling ±16B')
                : ('Filter #' + __wasm.filterRound + ': ' + remaining + ' remaining')
            );

            __emitWasm({
              stage: 'WASM_HEAP_DELTA_FILTERED',
              durationMs: durationMs,
              heapSourceLabel: heaps.label,
              heapByteLength: heaps.byteLength,
              filterRound: __wasm.filterRound,
              newValue: newVal,
              previousCount: prevTotal,
              totalMatches: remaining,
              heap32MatchCount: next32.length,
              heapF32MatchCount: nextF32.length,
              samples: samples,
              autoIsolated: autoIsolated
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'WASM filter failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Filter error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 3: Manually lock onto any surviving WASM candidate address and begin live 200 ms
     * polling of its neighboring floats (±4 to ±16 bytes).
     */
    fun buildInspectNeighborsScript(heapType: String, index: Int): String {
        val safeType = if (heapType == "HEAPF32") "'HEAPF32'" else "'HEAP32'"
        val safeIdx = index.coerceAtLeast(0)
        return """
        (function() {
          try {
            $WASM_RUNTIME
            const heaps = __resolveWasmHeaps();
            if (!heaps) {
              __emitWasm({ stage: 'WASM_HEAP_ERROR', message: 'WASM heap is not available on this page.' });
              return;
            }
            __startWasmNeighborPoll($safeType, $safeIdx);
            __setWasmStatus('COMPLETED', 'Polling ±16B around ' + $safeType + '[' + $safeIdx + ']');
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Neighbor Inspector error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Stops the live 200 ms neighboring memory polling loop cleanly.
     */
    fun buildStopNeighborPollScript(): String = """
        (function() {
          try {
            $WASM_RUNTIME
            __stopWasmNeighborPoll();
            __setWasmStatus('IDLE', 'WASM neighbor polling stopped');
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Stops polling and clears all stored candidate arrays in the WebView.
     */
    fun buildResetTrackerScript(): String = """
        (function() {
          try {
            $WASM_RUNTIME
            __wasm.scanSeq++;
            __stopWasmNeighborPoll();
            __wasm.candidates32 = [];
            __wasm.candidatesF32 = [];
            __wasm.totalMatches32 = 0;
            __wasm.totalMatchesF32 = 0;
            __wasm.filterRound = 0;
            __wasm.isolated = null;
            __wasm.pollTick = 0;
            __setWasmStatus('IDLE', 'WASM Heap Tracker reset');
          } catch (_) {}
        })();
    """.trimIndent()

    // ============================================================================================
    // WebAssembly Float Heap Delta Scanner (Step 2A / 2B — Float Mode targeting window.HEAPF32)
    // ============================================================================================

    private const val FLOAT_DELTA_RUNTIME = """
        const __fBridge = window.TelemetryBridge;
        const __fd = (window.__wvWasmFloatDelta = window.__wvWasmFloatDelta || {
          scanSeq: 0,
          sourceLabel: '',
          heapByteLength: 0,
          snapIndices: null,
          snapValues: null,
          snapCount: 0,
          candidateIndices: null,
          candidateBaselines: null,
          candidateClusters: null,
          candidateCount: 0,
          pinnedIndices: [],
          watchedItems: [],
          scanRound: 0,
          pollId: null,
          pollTick: 0
        });

        const __emitFloat = function(obj) {
          try {
            if (__fBridge && typeof __fBridge.logTelemetry === 'function') {
              __fBridge.logTelemetry('WASM', JSON.stringify(obj));
            }
          } catch (_) {}
        };

        const __setFloatStatus = function(status, detail) {
          try {
            if (__fBridge && typeof __fBridge.updateScanStatus === 'function') {
              __fBridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __yieldFloatSlice = function(ms) {
          return new Promise(function(resolve) {
            setTimeout(resolve, ms || 12);
          });
        };

        const __resolveFloatHeap = function() {
          const tryObj = function(obj, prefix) {
            if (!obj || typeof obj !== 'object') return null;
            try {
              if (obj.HEAPF32 instanceof Float32Array && obj.HEAPF32.buffer && obj.HEAPF32.buffer.byteLength >= 4) {
                return {
                  heapF32: obj.HEAPF32,
                  label: prefix + 'HEAPF32',
                  byteLength: obj.HEAPF32.buffer.byteLength
                };
              }
              const h32 = obj.HEAP32 || obj.HEAPU8 || obj.HEAP8;
              if (h32 && ArrayBuffer.isView(h32) && h32.buffer && h32.buffer.byteLength >= 4) {
                return {
                  heapF32: new Float32Array(h32.buffer),
                  label: prefix + 'HEAPF32 (buffer view)',
                  byteLength: h32.buffer.byteLength
                };
              }
              const mem = obj.wasmMemory || (obj.asm && obj.asm.memory) || (obj.wasmExports && obj.wasmExports.memory);
              if (typeof WebAssembly !== 'undefined' && mem instanceof WebAssembly.Memory && mem.buffer && mem.buffer.byteLength >= 4) {
                return {
                  heapF32: new Float32Array(mem.buffer),
                  label: prefix + 'wasmMemory Float32Array',
                  byteLength: mem.buffer.byteLength
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

        const __stopFloatWatch = function() {
          try {
            if (__fd.pollId !== null) {
              clearInterval(__fd.pollId);
              __fd.pollId = null;
            }
          } catch (_) {}
        };

        const __startFloatWatch = function() {
          __stopFloatWatch();
          __fd.pollTick = 0;

          const pushWatchTick = function() {
            try {
              const resolved = __resolveFloatHeap();
              if (!resolved) return;
              const hf32 = resolved.heapF32;
              const len = hf32.length;

              const mergedMap = new Map();
              const pinned = Array.isArray(__fd.pinnedIndices) ? __fd.pinnedIndices : [];
              for (let i = 0; i < pinned.length; i++) {
                const idx = pinned[i] | 0;
                if (idx >= 0 && idx < len) {
                  mergedMap.set(idx, {
                    heapIndex: idx,
                    baselineValue: hf32[idx],
                    clusterSize: 1,
                    axisHint: 'PIN',
                    isPinned: true
                  });
                }
              }

              const watched = Array.isArray(__fd.watchedItems) ? __fd.watchedItems : [];
              for (let i = 0; i < watched.length; i++) {
                const item = watched[i];
                const idx = item.heapIndex | 0;
                if (idx < 0 || idx >= len) continue;
                const existing = mergedMap.get(idx);
                mergedMap.set(idx, {
                  heapIndex: idx,
                  baselineValue: item.baselineValue,
                  clusterSize: item.clusterSize || 1,
                  axisHint: item.axisHint || (existing ? existing.axisHint : ''),
                  isPinned: existing ? true : false
                });
              }

              if (mergedMap.size === 0) return;

              __fd.pollTick++;
              const out = [];
              mergedMap.forEach(function(meta, idx) {
                const rawVal = hf32[idx];
                const cur = Number.isFinite(rawVal) ? Number(rawVal.toFixed(4)) : 0;
                const base = Number.isFinite(meta.baselineValue) ? Number(meta.baselineValue.toFixed(4)) : cur;
                const d = Number((cur - base).toFixed(4));
                out.push({
                  heapIndex: idx,
                  byteAddress: idx * 4,
                  baselineValue: base,
                  currentValue: cur,
                  delta: d,
                  clusterSize: meta.clusterSize,
                  axisHint: meta.axisHint,
                  isPinned: meta.isPinned
                });
              });

              const payload = {
                stage: 'WASM_FLOAT_WATCH_TICK',
                heapSourceLabel: resolved.label,
                heapByteLength: resolved.byteLength,
                tick: __fd.pollTick,
                candidates: out,
                locked: null
              };

              if (__fd.locked && typeof __fd.locked.xIndex === 'number') {
                const lx = __fd.locked.xIndex | 0;
                const ly = (typeof __fd.locked.yIndex === 'number') ? (__fd.locked.yIndex | 0) : -1;
                const lz = (typeof __fd.locked.zIndex === 'number') ? (__fd.locked.zIndex | 0) : -1;
                if (lx >= 0 && lx < len) {
                  const xv = Number.isFinite(hf32[lx]) ? Number(hf32[lx].toFixed(4)) : 0;
                  const yv = (ly >= 0 && ly < len && Number.isFinite(hf32[ly])) ? Number(hf32[ly].toFixed(4)) : 0;
                  const zv = (lz >= 0 && lz < len && Number.isFinite(hf32[lz])) ? Number(hf32[lz].toFixed(4)) : 0;
                  payload.locked = {
                    xIndex: lx,
                    yIndex: ly >= 0 ? ly : null,
                    zIndex: lz >= 0 ? lz : null,
                    xValue: xv,
                    yValue: yv,
                    zValue: zv
                  };
                  if (__fBridge && typeof __fBridge.updatePlayerCoords === 'function') {
                    __fBridge.updatePlayerCoords(
                      'window.HEAPF32[' + lx + (ly >= 0 ? ',' + ly : '') + (lz >= 0 ? ',' + lz : '') + ']',
                      xv,
                      yv,
                      zv
                    );
                  }
                }
              }

              if (__fBridge && typeof __fBridge.updateWasmFloatCandidates === 'function') {
                __fBridge.updateWasmFloatCandidates(JSON.stringify(payload));
              }
            } catch (_) {}
          };

          pushWatchTick();
          __fd.pollId = setInterval(pushWatchTick, 200);
        };
    """

    /**
     * Step 2A (Float Mode): Non-blocking, memory-safe baseline snapshot of `window.HEAPF32`.
     * Skips out-of-bounds and low immutable/guard regions, stores plausible coordinate-range
     * floats in compact TypedArrays (`Int32Array` + `Float32Array`), and runs a short standing-still
     * stability prune to automatically strip constantly ticking timers and frame clocks.
     */
    fun buildFloatBaselineSnapshotScript(): String = """
        (async function() {
          try {
            $FLOAT_DELTA_RUNTIME
            const resolved = __resolveFloatHeap();
            if (!resolved) {
              __setFloatStatus('ERROR', 'window.HEAPF32 not found');
              __emitFloat({
                stage: 'WASM_FLOAT_ERROR',
                message: 'Could not locate window.HEAPF32 (or Module.HEAPF32) on this page. Switch Step 2 to JS Objects mode if this page does not use WebAssembly.'
              });
              return;
            }

            __stopFloatWatch();
            const mySeq = ++__fd.scanSeq;
            const startMs = performance.now();
            const hf32 = resolved.heapF32;
            const len = hf32.length;

            // Skip low guard / immutable static data region while staying safe for small test heaps
            const START_INDEX = len > 4096 ? 256 : 0;
            const CHUNK_FLOATS = 262144; // 1 MB per async yield slice
            const MAX_SNAPSHOT = 1500000; // ~12 MB cap (Int32Array + Float32Array) for memory safety
            const MIN_ABS = 0.0001;
            const MAX_ABS = 500000.0;

            const idxChunks = [];
            const valChunks = [];
            const chunkLengths = [];
            let totalCaptured = 0;

            __setFloatStatus('RUNNING', 'Float Baseline: sampling ' + resolved.label + ' (0%)…');

            let lastPct = -1;
            for (let base = START_INDEX; base < len && totalCaptured < MAX_SNAPSHOT; base += CHUNK_FLOATS) {
              if (mySeq !== __fd.scanSeq) return;
              const end = Math.min(base + CHUNK_FLOATS, len);
              const remainingCap = MAX_SNAPSHOT - totalCaptured;
              const tempIdx = new Int32Array(Math.min(end - base, remainingCap));
              const tempVal = new Float32Array(tempIdx.length);
              let count = 0;

              for (let i = base; i < end && count < tempIdx.length; i++) {
                const v = hf32[i];
                if (v === v && v !== 0) {
                  const av = v < 0 ? -v : v;
                  if (av >= MIN_ABS && av <= MAX_ABS) {
                    tempIdx[count] = i;
                    tempVal[count] = v;
                    count++;
                  }
                }
              }

              if (count > 0) {
                idxChunks.push(tempIdx.subarray(0, count));
                valChunks.push(tempVal.subarray(0, count));
                chunkLengths.push(count);
                totalCaptured += count;
              }

              const pct = Math.min(85, Math.floor(((end - START_INDEX) / Math.max(1, len - START_INDEX)) * 85));
              if (pct >= lastPct + 15) {
                lastPct = pct;
                __setFloatStatus('RUNNING', 'Float Baseline ' + pct + '% (' + totalCaptured + ' floats)…');
              }

              if (end < len && totalCaptured < MAX_SNAPSHOT) {
                await __yieldFloatSlice(12);
              }
            }

            if (mySeq !== __fd.scanSeq) return;

            // Standing-still stability pass (120 ms): prune continuously ticking timers/clocks
            __setFloatStatus('RUNNING', 'Float Baseline: pruning standing jitter…');
            await __yieldFloatSlice(120);
            if (mySeq !== __fd.scanSeq) return;

            const refreshed = __resolveFloatHeap();
            const liveHf32 = refreshed ? refreshed.heapF32 : hf32;
            const liveLen = liveHf32.length;

            const stableIndices = new Int32Array(totalCaptured);
            const stableValues = new Float32Array(totalCaptured);
            let stableCount = 0;
            let prunedJitter = 0;

            for (let c = 0; c < idxChunks.length; c++) {
              if (mySeq !== __fd.scanSeq) return;
              const cIdx = idxChunks[c];
              const cVal = valChunks[c];
              const cLen = chunkLengths[c];
              for (let j = 0; j < cLen; j++) {
                const heapIdx = cIdx[j];
                const baseV = cVal[j];
                if (heapIdx >= 0 && heapIdx < liveLen) {
                  const nowV = liveHf32[heapIdx];
                  if (nowV === baseV || (Number.isFinite(nowV) && Math.abs(nowV - baseV) < 0.0005)) {
                    stableIndices[stableCount] = heapIdx;
                    stableValues[stableCount] = baseV;
                    stableCount++;
                  } else {
                    prunedJitter++;
                  }
                }
              }
              if (c % 2 === 1 && c + 1 < idxChunks.length) {
                await __yieldFloatSlice(8);
              }
            }

            if (mySeq !== __fd.scanSeq) return;

            __fd.sourceLabel = (refreshed ? refreshed.label : resolved.label);
            __fd.heapByteLength = (refreshed ? refreshed.byteLength : resolved.byteLength);
            __fd.snapIndices = stableIndices.subarray(0, stableCount);
            __fd.snapValues = stableValues.subarray(0, stableCount);
            __fd.snapCount = stableCount;
            __fd.candidateIndices = null;
            __fd.candidateBaselines = null;
            __fd.candidateClusters = null;
            __fd.candidateCount = 0;
            __fd.watchedItems = [];
            __fd.scanRound = 0;

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __setFloatStatus('COMPLETED', 'Float Baseline: ' + stableCount + ' stable floats (' + durationMs + 'ms)');
            __emitFloat({
              stage: 'WASM_FLOAT_BASELINE_CAPTURED',
              durationMs: durationMs,
              heapSourceLabel: __fd.sourceLabel,
              heapByteLength: __fd.heapByteLength,
              totalHeapFloats: len,
              sampledCount: stableCount,
              prunedJitterCount: prunedJitter,
              capped: totalCaptured >= MAX_SNAPSHOT
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Float baseline failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Float Baseline error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 2B (Float Mode): Non-blocking movement delta scanner comparing live `window.HEAPF32`
     * against the stored standing baseline snapshot to isolate positional coordinate floats
     * (`minDelta <= |delta| <= maxDelta`) and automatically stream top X/Y/Z candidates at 200 ms.
     */
    fun buildFloatDeltaScanScript(
        minDelta: Double = 0.05,
        maxDelta: Double = 250.0
    ): String {
        val minLit = if (minDelta.isFinite() && minDelta > 0.0) minDelta.toString() else "0.05"
        val maxLit = if (maxDelta.isFinite() && maxDelta > minDelta) maxDelta.toString() else "250.0"
        return """
        (async function() {
          try {
            $FLOAT_DELTA_RUNTIME
            if (!__fd.snapIndices || !__fd.snapValues || __fd.snapCount <= 0) {
              __setFloatStatus('IDLE', 'Capture Float Baseline first');
              __emitFloat({
                stage: 'WASM_FLOAT_ERROR',
                message: 'Stand still and tap 2A. Capture Float Baseline before scanning for movement deltas.'
              });
              return;
            }

            const resolved = __resolveFloatHeap();
            if (!resolved) {
              __setFloatStatus('ERROR', 'window.HEAPF32 unavailable');
              __emitFloat({
                stage: 'WASM_FLOAT_ERROR',
                message: 'window.HEAPF32 is no longer accessible on this page.'
              });
              return;
            }

            const mySeq = ++__fd.scanSeq;
            const startMs = performance.now();
            const hf32 = resolved.heapF32;
            const len = hf32.length;
            const MIN_DELTA = Number($minLit);
            const MAX_DELTA = Number($maxLit);
            const MAX_ABS = 500000.0;
            const CHUNK_CHECK = 262144;
            const MAX_CANDIDATES = 60000;

            // On round 1 scan the full baseline; on subsequent rounds refine the surviving candidate set
            const useRefinement = (__fd.scanRound > 0 && __fd.candidateIndices && __fd.candidateCount > 0 && __fd.candidateCount < __fd.snapCount);
            const srcIndices = useRefinement ? __fd.candidateIndices : __fd.snapIndices;
            const srcBaselines = useRefinement ? __fd.candidateBaselines : __fd.snapValues;
            const srcLen = useRefinement ? __fd.candidateCount : __fd.snapCount;

            const hitIndices = [];
            const hitBaselines = [];
            const hitCurrents = [];
            const hitDeltas = [];
            let totalMatches = 0;

            __setFloatStatus('RUNNING', 'Scanning HEAPF32 movement deltas…');

            for (let base = 0; base < srcLen; base += CHUNK_CHECK) {
              if (mySeq !== __fd.scanSeq) return;
              const end = Math.min(base + CHUNK_CHECK, srcLen);
              for (let j = base; j < end; j++) {
                const idx = srcIndices[j];
                if (idx < 0 || idx >= len) continue;
                const cur = hf32[idx];
                if (!Number.isFinite(cur)) continue;
                const absCur = cur < 0 ? -cur : cur;
                if (absCur > MAX_ABS) continue;
                const prev = srcBaselines[j];
                const diff = cur - prev;
                const absDiff = diff < 0 ? -diff : diff;
                if (absDiff >= MIN_DELTA && absDiff <= MAX_DELTA) {
                  totalMatches++;
                  if (hitIndices.length < MAX_CANDIDATES) {
                    hitIndices.push(idx);
                    hitBaselines.push(prev);
                    hitCurrents.push(cur);
                    hitDeltas.push(diff);
                  }
                }
              }
              if (end < srcLen) {
                await __yieldFloatSlice(10);
              }
            }

            if (mySeq !== __fd.scanSeq) return;

            // Positional cluster scoring: coordinates usually sit at consecutive ±1..±2 float slots (X, Y, Z)
            const hitIndexSet = new Set();
            for (let i = 0; i < hitIndices.length; i++) {
              hitIndexSet.add(hitIndices[i]);
            }

            const ranked = [];
            for (let i = 0; i < hitIndices.length; i++) {
              const idx = hitIndices[i];
              let neighborsMoving = 1;
              if (hitIndexSet.has(idx - 1)) neighborsMoving++;
              if (hitIndexSet.has(idx + 1)) neighborsMoving++;
              if (hitIndexSet.has(idx - 2)) neighborsMoving++;
              if (hitIndexSet.has(idx + 2)) neighborsMoving++;

              // Favor contiguous 2D/3D coordinate triplets and moderate world-scale values
              const absCur = Math.abs(hitCurrents[i]);
              const scaleBonus = (absCur >= 0.5 && absCur <= 50000) ? 1.5 : 0.2;
              const score = (neighborsMoving * 10) + scaleBonus;
              ranked.push({
                heapIndex: idx,
                byteAddress: idx * 4,
                baselineValue: Number(hitBaselines[i].toFixed(4)),
                currentValue: Number(hitCurrents[i].toFixed(4)),
                delta: Number(hitDeltas[i].toFixed(4)),
                clusterSize: neighborsMoving,
                score: score,
                axisHint: ''
              });
            }

            ranked.sort(function(a, b) {
              if (b.score !== a.score) return b.score - a.score;
              return a.heapIndex - b.heapIndex;
            });

            // Build top watched list (up to 12 offsets), auto-expanding a best Vec3 cluster (X, Y, Z)
            const topWatched = [];
            const addedSet = new Set();
            if (ranked.length > 0) {
              const anchor = ranked[0];
              // Check if anchor sits in a contiguous Vec3 cluster (e.g. X, Y, Z where Y may be flat elevation)
              const startIdx = hitIndexSet.has(anchor.heapIndex - 2)
                ? anchor.heapIndex - 2
                : (hitIndexSet.has(anchor.heapIndex - 1) ? anchor.heapIndex - 1 : anchor.heapIndex);
              const axisLabels = ['X', 'Y', 'Z'];
              for (let k = 0; k < 3; k++) {
                const slotIdx = startIdx + k;
                if (slotIdx < 0 || slotIdx >= len) continue;
                const fv = hf32[slotIdx];
                if (!Number.isFinite(fv) || Math.abs(fv) > MAX_ABS) continue;
                let existingHit = null;
                for (let r = 0; r < Math.min(ranked.length, 400); r++) {
                  if (ranked[r].heapIndex === slotIdx) {
                    existingHit = ranked[r];
                    break;
                  }
                }
                const slotEntry = existingHit ? {
                  heapIndex: existingHit.heapIndex,
                  byteAddress: existingHit.byteAddress,
                  baselineValue: existingHit.baselineValue,
                  currentValue: existingHit.currentValue,
                  delta: existingHit.delta,
                  clusterSize: existingHit.clusterSize,
                  axisHint: axisLabels[k]
                } : {
                  heapIndex: slotIdx,
                  byteAddress: slotIdx * 4,
                  baselineValue: Number(fv.toFixed(4)),
                  currentValue: Number(fv.toFixed(4)),
                  delta: 0,
                  clusterSize: anchor.clusterSize,
                  axisHint: axisLabels[k]
                };
                topWatched.push(slotEntry);
                addedSet.add(slotIdx);
              }
            }

            for (let i = 0; i < ranked.length && topWatched.length < 12; i++) {
              const item = ranked[i];
              if (addedSet.has(item.heapIndex)) continue;
              addedSet.add(item.heapIndex);
              topWatched.push({
                heapIndex: item.heapIndex,
                byteAddress: item.byteAddress,
                baselineValue: item.baselineValue,
                currentValue: item.currentValue,
                delta: item.delta,
                clusterSize: item.clusterSize,
                axisHint: ''
              });
            }

            // Persist narrowed TypedArrays with updated baselines for iterative multi-step refinement
            const nextCount = hitIndices.length;
            const nextIdxArr = new Int32Array(nextCount);
            const nextBaseArr = new Float32Array(nextCount);
            for (let i = 0; i < nextCount; i++) {
              nextIdxArr[i] = hitIndices[i];
              nextBaseArr[i] = hitCurrents[i];
            }

            __fd.sourceLabel = resolved.label;
            __fd.heapByteLength = resolved.byteLength;
            __fd.candidateIndices = nextIdxArr;
            __fd.candidateBaselines = nextBaseArr;
            __fd.candidateCount = nextCount;
            __fd.watchedItems = topWatched;
            __fd.scanRound = (__fd.scanRound || 0) + 1;

            if (topWatched.length > 0) {
              __startFloatWatch();
            } else {
              __stopFloatWatch();
            }

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __setFloatStatus(
              totalMatches > 0 ? 'COMPLETED' : 'IDLE',
              totalMatches > 0
                ? ('Float Deltas #' + __fd.scanRound + ': ' + totalMatches + ' matches • Watching top ' + topWatched.length)
                : ('Float Deltas #' + __fd.scanRound + ': 0 matches — walk & retry')
            );
            __emitFloat({
              stage: 'WASM_FLOAT_DELTA_SCANNED',
              durationMs: durationMs,
              heapSourceLabel: resolved.label,
              heapByteLength: resolved.byteLength,
              scanRound: __fd.scanRound,
              comparedCount: srcLen,
              totalMatches: totalMatches,
              minDelta: MIN_DELTA,
              maxDelta: MAX_DELTA,
              candidates: topWatched
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Float delta scan failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Float Delta Scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Synchronizes pinned `HEAPF32` indices into the live 200 ms watcher so pinned coordinates
     * remain visible even across subsequent delta refinement rounds.
     */
    fun buildSyncPinnedFloatsScript(pinnedIndices: Collection<Int>): String {
        val arrLiteral = pinnedIndices.distinct().filter { it >= 0 }.take(24).joinToString(",")
        return """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            __fd.pinnedIndices = [$arrLiteral];
            if ((__fd.pinnedIndices.length > 0 || (__fd.watchedItems && __fd.watchedItems.length > 0)) && __fd.pollId === null) {
              __startFloatWatch();
            }
          } catch (_) {}
        })();
        """.trimIndent()
    }

    /**
     * Stops the 200 ms Live Float Watcher interval cleanly.
     */
    fun buildStopFloatWatchScript(): String = """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            __stopFloatWatch();
            __setFloatStatus('IDLE', 'Live Float Watcher stopped');
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Releases the stored `HEAPF32` TypedArray baseline snapshot and stops live watching.
     */
    fun buildResetFloatDeltaScript(): String = """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            __fd.scanSeq++;
            __stopFloatWatch();
            __fd.snapIndices = null;
            __fd.snapValues = null;
            __fd.snapCount = 0;
            __fd.candidateIndices = null;
            __fd.candidateBaselines = null;
            __fd.candidateClusters = null;
            __fd.candidateCount = 0;
            __fd.pinnedIndices = [];
            __fd.watchedItems = [];
            __fd.locked = null;
            __fd.scanRound = 0;
            __fd.pollTick = 0;
            __setFloatStatus('IDLE', 'Float Heap Delta Scanner reset');
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Locks specific `window.HEAPF32` indices as the confirmed player X/Y/Z coordinate triplet
     * and starts/continues 200 ms live coordinate polling through TelemetryBridge.
     */
    fun buildLockFloatCoordsScript(
        xIndex: Int,
        yIndex: Int?,
        zIndex: Int?
    ): String {
        val safeX = xIndex.coerceAtLeast(0)
        val safeY = yIndex?.takeIf { it >= 0 }?.toString() ?: "null"
        val safeZ = zIndex?.takeIf { it >= 0 }?.toString() ?: "null"
        return """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            const resolved = __resolveFloatHeap();
            if (!resolved) {
              __emitFloat({ stage: 'WASM_FLOAT_ERROR', message: 'window.HEAPF32 is not accessible to lock coordinates.' });
              return;
            }
            const hf32 = resolved.heapF32;
            const len = hf32.length;
            if ($safeX >= len) {
              __emitFloat({ stage: 'WASM_FLOAT_ERROR', message: 'Locked float index is out of bounds.' });
              return;
            }
            __fd.locked = { xIndex: $safeX, yIndex: $safeY, zIndex: $safeZ };
            const lockIndices = [$safeX${if (yIndex != null && yIndex >= 0) ",$yIndex" else ""}${if (zIndex != null && zIndex >= 0) ",$zIndex" else ""}];
            const existingPins = Array.isArray(__fd.pinnedIndices) ? __fd.pinnedIndices : [];
            const pinSet = new Set(existingPins.concat(lockIndices));
            __fd.pinnedIndices = Array.from(pinSet).slice(0, 24);
            __startFloatWatch();
            __setFloatStatus('COMPLETED', 'Locked HEAPF32[' + lockIndices.join(',') + '] • Live 200ms');
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Lock Float Coords error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Unlocks the locked `HEAPF32` coordinate triplet while keeping the Float Watcher functional.
     */
    fun buildUnlockFloatCoordsScript(): String = """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            __fd.locked = null;
            __setFloatStatus('IDLE', 'Unlocked WASM float coordinates');
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Strictly read-only, non-blocking Level-1 & Level-2 WASM Pointer Scanner over `Uint32Array`
     * (`HEAPU32` derived from linear memory). Locates pointers holding a struct base address within
     * `0..maxStructOffset` bytes before `targetByteAddress`, then scans for parent pointers to those
     * Level-1 pointers so coordinates can be located persistently across reloads/sessions.
     */
    fun buildPointerScanScript(
        targetByteAddress: Long,
        maxStructOffset: Int = 256
    ): String {
        val safeTarget = targetByteAddress.coerceAtLeast(0L)
        val safeMaxOffset = maxStructOffset.coerceIn(0, 1024)
        return """
        (async function() {
          try {
            $FLOAT_DELTA_RUNTIME
            const resolved = __resolveFloatHeap();
            if (!resolved) {
              __setFloatStatus('ERROR', 'WASM heap unavailable for pointer scan');
              __emitFloat({
                stage: 'WASM_POINTER_ERROR',
                message: 'Could not resolve WASM linear memory buffer for pointer scanning.'
              });
              return;
            }

            const mySeq = ++__fd.scanSeq;
            const startMs = performance.now();
            const hf32 = resolved.heapF32;
            const hu32 = new Uint32Array(hf32.buffer);
            const lenU32 = hu32.length;
            const lenF32 = hf32.length;
            const TARGET_ADDR = $safeTarget;
            const MAX_OFFSET = $safeMaxOffset;
            const MIN_BASE = Math.max(4, TARGET_ADDR - MAX_OFFSET);
            const CHUNK_U32 = 262144; // 1 MB per non-blocking async yield
            const STATIC_REGION_LIMIT = 2 * 1024 * 1024; // Low 2 MB typically holds WASM static globals & data
            const MAX_L1_STORED = 2000;

            const formatHex = function(n) {
              return '0x' + (n >>> 0).toString(16).toUpperCase().padStart(8, '0');
            };
            const formatOff = function(n) {
              return '0x' + (n >>> 0).toString(16).toUpperCase();
            };

            const readXYZ = function(byteAddr) {
              const fIdx = (byteAddr >>> 2);
              const x = (fIdx >= 0 && fIdx < lenF32 && Number.isFinite(hf32[fIdx])) ? Number(hf32[fIdx].toFixed(4)) : 0;
              const y = (fIdx + 1 < lenF32 && Number.isFinite(hf32[fIdx + 1])) ? Number(hf32[fIdx + 1].toFixed(4)) : 0;
              const z = (fIdx + 2 < lenF32 && Number.isFinite(hf32[fIdx + 2])) ? Number(hf32[fIdx + 2].toFixed(4)) : 0;
              return { x: x, y: y, z: z };
            };

            __setFloatStatus('RUNNING', 'Pointer Scan L1: searching HEAPU32 for ' + formatHex(TARGET_ADDR) + '…');

            // Pass 1: Level-1 pointers (ptrVal in [TARGET_ADDR - MAX_OFFSET, TARGET_ADDR], 4-byte aligned)
            const l1Hits = [];
            let totalL1 = 0;
            for (let base = 0; base < lenU32; base += CHUNK_U32) {
              if (mySeq !== __fd.scanSeq) return;
              const end = Math.min(base + CHUNK_U32, lenU32);
              for (let i = base; i < end; i++) {
                const v = hu32[i];
                if (v >= MIN_BASE && v <= TARGET_ADDR && ((TARGET_ADDR - v) & 3) === 0) {
                  const ptrByteAddr = i * 4;
                  if (ptrByteAddr === TARGET_ADDR) continue;
                  totalL1++;
                  if (l1Hits.length < MAX_L1_STORED) {
                    const offset = TARGET_ADDR - v;
                    const isStatic = ptrByteAddr > 0 && ptrByteAddr < STATIC_REGION_LIMIT;
                    l1Hits.push({
                      ptrAddr: ptrByteAddr,
                      pointsTo: v,
                      offset: offset,
                      isStatic: isStatic
                    });
                  }
                }
              }
              if (end < lenU32) {
                await __yieldFloatSlice(12);
              }
            }

            if (mySeq !== __fd.scanSeq) return;

            // Prioritize static-region pointers and compact struct offsets (0..64B)
            l1Hits.sort(function(a, b) {
              if (a.isStatic !== b.isStatic) return a.isStatic ? -1 : 1;
              if (a.offset !== b.offset) return a.offset - b.offset;
              return a.ptrAddr - b.ptrAddr;
            });

            // Pass 2: Level-2 parent pointers for top L1 pointer addresses (finds *(*(base) + off1) + off2)
            const MAX_L2_TARGETS = 48;
            const L2_MAX_OFFSET = 128;
            const l1ForL2 = l1Hits.slice(0, MAX_L2_TARGETS);
            const l2Hits = [];
            let totalL2 = 0;

            if (l1ForL2.length > 0) {
              let minL1Target = 0xFFFFFFFF;
              let maxL1Target = 0;
              for (let k = 0; k < l1ForL2.length; k++) {
                const a = l1ForL2[k].ptrAddr;
                const lo = Math.max(4, a - L2_MAX_OFFSET);
                if (lo < minL1Target) minL1Target = lo;
                if (a > maxL1Target) maxL1Target = a;
              }

              __setFloatStatus('RUNNING', 'Pointer Scan L2: tracing parent chains for ' + l1ForL2.length + ' L1 hits…');
              for (let base = 0; base < lenU32; base += CHUNK_U32) {
                if (mySeq !== __fd.scanSeq) return;
                const end = Math.min(base + CHUNK_U32, lenU32);
                for (let i = base; i < end; i++) {
                  const v = hu32[i];
                  if (v < minL1Target || v > maxL1Target || (v & 3) !== 0) continue;
                  for (let k = 0; k < l1ForL2.length; k++) {
                    const child = l1ForL2[k];
                    const off1 = child.ptrAddr - v;
                    if (off1 >= 0 && off1 <= L2_MAX_OFFSET && (off1 & 3) === 0) {
                      const p2Addr = i * 4;
                      if (p2Addr === child.ptrAddr || p2Addr === TARGET_ADDR) continue;
                      totalL2++;
                      if (l2Hits.length < 250) {
                        l2Hits.push({
                          baseAddr: p2Addr,
                          off1: off1,
                          off2: child.offset,
                          isStatic: p2Addr > 0 && p2Addr < STATIC_REGION_LIMIT
                        });
                      }
                      break;
                    }
                  }
                }
                if (end < lenU32) {
                  await __yieldFloatSlice(12);
                }
              }
            }

            if (mySeq !== __fd.scanSeq) return;

            l2Hits.sort(function(a, b) {
              if (a.isStatic !== b.isStatic) return a.isStatic ? -1 : 1;
              const sumA = a.off1 + a.off2;
              const sumB = b.off1 + b.off2;
              if (sumA !== sumB) return sumA - sumB;
              return a.baseAddr - b.baseAddr;
            });

            const xyz = readXYZ(TARGET_ADDR);
            const chains = [];

            // Emit top static L1 pointers first, then top L2 chains, then remaining L1 pointers (cap 20 total)
            const staticL1 = l1Hits.filter(function(h) { return h.isStatic; }).slice(0, 8);
            const heapL1 = l1Hits.filter(function(h) { return !h.isStatic; }).slice(0, 6);
            const topL2 = l2Hits.slice(0, 8);

            for (let i = 0; i < staticL1.length; i++) {
              const h = staticL1[i];
              chains.push({
                id: 'L1_' + formatHex(h.ptrAddr) + '_+' + formatOff(h.offset),
                depth: 1,
                basePointerAddress: h.ptrAddr,
                offsets: [h.offset],
                resolvedTargetAddress: TARGET_ADDR,
                resolvedX: xyz.x,
                resolvedY: xyz.y,
                resolvedZ: xyz.z,
                isLikelyStatic: true,
                isVerifiedLive: true,
                expression: '*(' + formatHex(h.ptrAddr) + ') + ' + formatOff(h.offset)
              });
            }

            for (let i = 0; i < topL2.length && chains.length < 16; i++) {
              const h = topL2[i];
              chains.push({
                id: 'L2_' + formatHex(h.baseAddr) + '_+' + formatOff(h.off1) + '_+' + formatOff(h.off2),
                depth: 2,
                basePointerAddress: h.baseAddr,
                offsets: [h.off1, h.off2],
                resolvedTargetAddress: TARGET_ADDR,
                resolvedX: xyz.x,
                resolvedY: xyz.y,
                resolvedZ: xyz.z,
                isLikelyStatic: h.isStatic,
                isVerifiedLive: true,
                expression: '*(*(' + formatHex(h.baseAddr) + ') + ' + formatOff(h.off1) + ') + ' + formatOff(h.off2)
              });
            }

            for (let i = 0; i < heapL1.length && chains.length < 20; i++) {
              const h = heapL1[i];
              chains.push({
                id: 'L1_' + formatHex(h.ptrAddr) + '_+' + formatOff(h.offset),
                depth: 1,
                basePointerAddress: h.ptrAddr,
                offsets: [h.offset],
                resolvedTargetAddress: TARGET_ADDR,
                resolvedX: xyz.x,
                resolvedY: xyz.y,
                resolvedZ: xyz.z,
                isLikelyStatic: false,
                isVerifiedLive: true,
                expression: '*(' + formatHex(h.ptrAddr) + ') + ' + formatOff(h.offset)
              });
            }

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __setFloatStatus(
              chains.length > 0 ? 'COMPLETED' : 'IDLE',
              'Pointer Scan: L1=' + totalL1 + ', L2=' + totalL2 + ' (' + durationMs + 'ms)'
            );
            __emitFloat({
              stage: 'WASM_POINTER_SCAN_COMPLETE',
              durationMs: durationMs,
              heapSourceLabel: resolved.label,
              targetByteAddress: TARGET_ADDR,
              maxStructOffset: MAX_OFFSET,
              totalL1: totalL1,
              totalL2: totalL2,
              chains: chains
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Pointer scan failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Pointer Scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Re-dereferences saved/discovered WASM pointer chains against live `HEAPU32` / `HEAPF32`
     * to verify whether each chain persistently resolves to valid live X, Y, Z coordinates.
     */
    fun buildVerifyPointerChainsScript(chainsJsonArray: String): String = """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            const resolved = __resolveFloatHeap();
            if (!resolved) {
              __emitFloat({ stage: 'WASM_POINTER_ERROR', message: 'WASM heap unavailable to verify pointer chains.' });
              return;
            }
            const hf32 = resolved.heapF32;
            const hu32 = new Uint32Array(hf32.buffer);
            const byteLen = hf32.buffer.byteLength;
            const lenU32 = hu32.length;
            const lenF32 = hf32.length;
            const inputChains = $chainsJsonArray;
            const results = [];
            let validCount = 0;
            let zeroCount = 0;

            for (let i = 0; i < inputChains.length; i++) {
              const c = inputChains[i];
              let addr = c.basePointerAddress >>> 0;
              let ok = true;
              const offs = Array.isArray(c.offsets) ? c.offsets : [];
              for (let step = 0; step < offs.length; step++) {
                const uIdx = addr >>> 2;
                if (addr === 0 || (addr & 3) !== 0 || uIdx >= lenU32) {
                  ok = false;
                  break;
                }
                const deref = hu32[uIdx] >>> 0;
                const nextAddr = deref + (offs[step] | 0);
                if (deref === 0 || nextAddr <= 0 || nextAddr >= byteLen || (nextAddr & 3) !== 0) {
                  ok = false;
                  break;
                }
                addr = nextAddr;
              }

              let x = 0, y = 0, z = 0;
              let status = 'STALE';
              if (ok) {
                const fIdx = addr >>> 2;
                if (fIdx < lenF32 && Number.isFinite(hf32[fIdx]) && Math.abs(hf32[fIdx]) <= 500000) {
                  x = Number(hf32[fIdx].toFixed(4));
                  y = (fIdx + 1 < lenF32 && Number.isFinite(hf32[fIdx + 1])) ? Number(hf32[fIdx + 1].toFixed(4)) : 0;
                  z = (fIdx + 2 < lenF32 && Number.isFinite(hf32[fIdx + 2])) ? Number(hf32[fIdx + 2].toFixed(4)) : 0;
                  const isZero = Math.abs(x) < 0.00001 && Math.abs(y) < 0.00001 && Math.abs(z) < 0.00001;
                  if (isZero) {
                    status = 'ZERO';
                    ok = false;
                    zeroCount++;
                  } else {
                    status = 'LIVE';
                    validCount++;
                  }
                } else {
                  ok = false;
                  status = 'STALE';
                }
              }

              results.push({
                id: c.id,
                status: status,
                resolvedTargetAddress: (status === 'LIVE' || status === 'ZERO') ? addr : 0,
                resolvedX: x,
                resolvedY: y,
                resolvedZ: z,
                isVerifiedLive: ok
              });
            }

            __setFloatStatus('COMPLETED', 'Verified ' + validCount + '/' + results.length + ' live (' + zeroCount + ' zeroed)');
            __emitFloat({
              stage: 'WASM_POINTER_CHAINS_VERIFIED',
              validCount: validCount,
              zeroCount: zeroCount,
              totalCount: results.length,
              results: results
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Verify Pointer Chains error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()
}
