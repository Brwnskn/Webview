package com.example

import org.json.JSONObject

/**
 * Zero-freeze, filtered & player-anchored JavaScript diagnostic routines with direct
 * live object-reference polling (200 ms) and multi-scan candidate discovery.
 *
 * Strictly observational and read-only: never dispatches synthetic input events,
 * never modifies game state, and excludes WebAssembly linear memory heaps during JS scans.
 */
object DiagnosticsScripts {

    private const val SHARED_RUNTIME = """
        const __bridge = window.TelemetryBridge;
        const __state = (window.__wvDiagState = window.__wvDiagState || {
          scanSeq: 0,
          customGlobals: [],
          shallowPaths: [],
          baseline: null,
          baselineTimeMs: 0,
          isolatedPaths: [],
          discoveredPlayerPath: null,
          liveRef: null,
          discoveredEntityArrayPath: null,
          entityProjectionMode: '',
          discoveredEntities: [],
          livePollId: null,
          scopeBatch: null
        });

        const __TARGETED_ENGINE_ROOTS = [
          'game', 'app', 'engine', 'Phaser', 'PIXI', 'BABYLON', 'Module',
          'THREE', 'unityInstance', 'pc', 'cc',
          'Game', 'App', 'Engine', 'World', 'world', 'Scene', 'scene',
          'Client', 'client', 'Player', 'player', 'Main', 'main', 'Core', 'core', 'State', 'state'
        ];

        const __KNOWN_ENGINE_GLOBALS = new Set([
          'THREE', 'BABYLON', 'PIXI', 'Phaser', 'pc', 'cc', 'Module', 'unityInstance',
          'Game', 'game', 'App', 'app', 'Engine', 'engine', 'Scene', 'scene',
          'World', 'world', 'Player', 'player', 'Client', 'client', 'Net', 'net',
          'Hero', 'hero', 'Actor', 'actor', 'Main', 'main', 'Core', 'core', 'State', 'state'
        ]);

        const __WEB_API_PREFIX_RE = /^(HTML|SVG|WebGL|webkit|WebKit|on[a-z]|CSS|DOM|Audio|MIDI|RTC|IDB|XR|GPU|USB|HID|Bluetooth|Media|Speech|Presentation|Payment|Credential|Performance|Navigation|Animation|Font|Style|Event|Pointer|Touch|Mouse|Keyboard|Input|Wheel|Focus|UI|Text|Range|Selection|Node|Element|Attr|CharacterData|Document|Window|Screen|History|Location|Navigator|Storage|Crypto|Subtle|Canvas|Offscreen|Image|Path2D|Video|File|Blob|URL|FormData|Headers|Request|Response|Readable|Writable|Transform|Compression|Decompression|Abort|Custom|Shadow|Mutation|Resize|Intersection|Reporting|Broadcast|Message|Worker|Shared|Service|Cache|Cookie|Lock|WakeLock|Permissions|Geolocation|Sensor|Clipboard|Notification|Push|Sync|Background|VisualViewport|VirtualKeyboard|Launch|Scheduler|Trusted|Task|Atomics|Reflect|Intl|Proxy|BigInt|Float|Int|Uint|Array|DataView|Map|Set|Weak|Promise|Symbol|RegExp|Error|Eval|Reference|Syntax|Type|URI|Aggregate|Finalization|WebAssembly|XML|XPath|XSLT|VTTCue|Track|TimeRanges|Validity|BarProp|CryptoKey|External|Gamepad|Idle|Ink|Layout|MathML|NDEF|NFC|OTPCredential|Periodic|PictureInPicture|PublicKey|Sanitizer|Serial|SourceBuffer|Toggle|ViewTransition|Worklet)/;
        const __WEB_API_SUFFIX_RE = /^[A-Z][A-Za-z0-9]*(Event|Element|Error|List|Map|Set|Array|Stream|Controller|Reader|Writer|Observer|Entry|Info|Config|Options|Init|Prototype|Interface|Provider|Registry|Target|Source|Buffer|Context|Encoder|Decoder|Track|Recorder|Capabilities|Settings|Metrics|Rect|Point|Matrix|Quad|Filter|Node|Param|Collection|Declaration|Rule|Sheet|Style|Channel|Port|Store|Transaction|Cursor|Request|Response|Key|Handle|Device|Adapter|Queue|Pipeline|Shader|Texture|Sampler|BindGroup|Command)$/;

        const __STANDARD_EXACT = new Set([
          'window','self','globalThis','document','name','location','customElements','history',
          'navigation','locationbar','menubar','personalbar','scrollbars','statusbar','toolbar',
          'status','closed','frames','length','top','opener','parent','frameElement','navigator',
          'origin','external','screen','innerWidth','innerHeight','scrollX','pageXOffset',
          'scrollY','pageYOffset','visualViewport','screenX','screenY','outerWidth','outerHeight',
          'devicePixelRatio','event','clientInformation','screenLeft','screenTop','styleMedia',
          'trustedTypes','performance','crypto','indexedDB','sessionStorage','localStorage',
          'isSecureContext','crossOriginIsolated','scheduler','alert','atob','blur','btoa',
          'cancelAnimationFrame','cancelIdleCallback','captureEvents','clearInterval','clearTimeout',
          'close','confirm','createImageBitmap','fetch','find','focus','getComputedStyle',
          'getSelection','matchMedia','moveBy','moveTo','open','postMessage','print','prompt',
          'queueMicrotask','releaseEvents','reportError','requestAnimationFrame',
          'requestIdleCallback','resizeBy','resizeTo','scroll','scrollBy','scrollTo','setInterval',
          'setTimeout','stop','structuredClone','chrome','caches','cookieStore','speechSynthesis',
          'credentialless','originAgentCluster','fence','launchQueue','sharedStorage',
          'documentPictureInPicture','fetchLater','getScreenDetails','queryLocalFonts',
          'showDirectoryPicker','showOpenFilePicker','showSaveFilePicker',
          'TelemetryBridge','__wvDiagState','console','undefined','NaN','Infinity',
          'Object','Function','Boolean','Number','String','Date','JSON','Math',
          'parseInt','parseFloat','isNaN','isFinite','decodeURI','decodeURIComponent',
          'encodeURI','encodeURIComponent','escape','unescape','eval'
        ]);

        const __BUILTIN_BLACKLIST_RE = /^(window|document|navigator|location|screen|history|localStorage|sessionStorage|performance|console|indexedDB|isSecureContext|atob|btoa|fetch|alert|confirm|prompt|setTimeout|clearTimeout|setInterval|clearInterval|requestAnimationFrame|cancelAnimationFrame|crypto|Event|CustomEvent|WebSocket|Iterator|AsyncDisposableStack|DisposableStack|Option|FeaturePolicy|ViewTimeline|UserActivation|TreeWalker|Subscriber|StaticRange|ScrollTimeline|Scheduling|ReportBody|ProcessingInstruction|Plugin|PermissionStatus|Observable|NetworkInformation|MimeType|LargestContentfulPaint|KeyframeEffect|DelegatedInkTrailPresenter|DataTransferItem|DataTransfer|\d+)$/i;

        const __HEURISTIC_KEY_RE = /(player|actor|scene|game|pos|coord|hero|net|camera|cam|entity|entities|world|avatar|char|unit|mob|monster|npc|enemy|enemies|state|transform|vec|loc|vel|rot|manager|engine|stage|renderer|level|map)/i;
        const __TIME_KEY_RE = /(time|timestamp|elapsed|clock|frame|tick|now|date|epoch|ms|fps|ping|latency|delta|dt)/i;
        const __COORD_AXIS_RE = /(\.(x|y|z|w|vx|vy|vz|px|py|pz|lat|lng|lon|left|top|tx|ty|tz)|\[[0-2]\]$)/i;
        const __WASM_MEMORY_KEYS = new Set([
          'HEAP8', 'HEAPU8', 'HEAP16', 'HEAPU16', 'HEAP32', 'HEAPU32',
          'HEAPF32', 'HEAPF64', 'HEAP64', 'HEAPU64',
          'wasmTableMirror', 'wasmTable', 'wasmMemory', 'wasmExports', 'wasmBinary'
        ]);
        const __WASM_MEMORY_KEY_RE = /(^|[.\[])(HEAP(U?8|U?16|U?32|F32|F64|U?64)|wasmTableMirror|wasmTable|wasmMemory|wasmExports|wasmBinary)([.\]]|$)/;
        const __RENDER_BUFFER_CTOR_RE = /(Array$|Buffer$|DataView$|WebAssembly|Memory$|Table$|WebGL|GPU|Texture$|Shader$|Framebuffer$|Renderbuffer$|ImageData$|ImageBitmap$|Audio|Blob$|File$)/;
        const __CHUNK_BUDGET_MS = 4.0;
        const __LIVE_POLL_INTERVAL_MS = 200;

        /**
         * Returns true if a property key or path targets WebAssembly heap/table memory structures.
         */
        const __isExcludedWasmKey = function(keyOrPath) {
          if (!keyOrPath || typeof keyOrPath !== 'string') return false;
          return __WASM_MEMORY_KEYS.has(keyOrPath) || __WASM_MEMORY_KEY_RE.test(keyOrPath);
        };

        /**
         * Returns true for TypedArrays, ArrayBuffers, SharedArrayBuffers, DataViews, WebAssembly
         * Memory/Table/Instance/Module objects, raw byte arrays, and WebGL/rendering buffers.
         */
        const __isExcludedBinaryOrWasmObject = function(val) {
          if (!val || typeof val !== 'object') return false;
          try {
            if (ArrayBuffer.isView(val)) return true;
            if (typeof ArrayBuffer !== 'undefined' && val instanceof ArrayBuffer) return true;
            if (typeof SharedArrayBuffer !== 'undefined' && val instanceof SharedArrayBuffer) return true;
            if (typeof WebAssembly !== 'undefined') {
              if (WebAssembly.Memory && val instanceof WebAssembly.Memory) return true;
              if (WebAssembly.Table && val instanceof WebAssembly.Table) return true;
              if (WebAssembly.Instance && val instanceof WebAssembly.Instance) return true;
              if (WebAssembly.Module && val instanceof WebAssembly.Module) return true;
            }
            if (Array.isArray(val)) return true;
            if (val instanceof Map || val instanceof Set || val instanceof WeakMap || val instanceof WeakSet) return true;
            if (typeof ImageData !== 'undefined' && val instanceof ImageData) return true;
            if (typeof ImageBitmap !== 'undefined' && val instanceof ImageBitmap) return true;
            const ctor = val.constructor;
            if (ctor && ctor !== Object) {
              const ctorName = typeof ctor.name === 'string' ? ctor.name : '';
              if (ctorName && __RENDER_BUFFER_CTOR_RE.test(ctorName)) return true;
            }
          } catch (_) {
            return true;
          }
          return false;
        };

        /**
         * Strictly permits only plain JS objects and non-native custom game objects for Step 2A/2B
         * numeric baseline snapshotting (never TypedArrays, ArrayBuffers, raw arrays, DOM, or WASM).
         */
        const __isSafeBaselineObject = function(val) {
          if (!val || typeof val !== 'object') return false;
          if (__isSpecialHostObject(val)) return false;
          if (__isExcludedBinaryOrWasmObject(val)) return false;
          try {
            const proto = Object.getPrototypeOf(val);
            if (proto === null || proto === Object.prototype) return true;
            const ctor = val.constructor;
            if (typeof ctor === 'function' && ctor !== Object && __isNativeFunction(ctor)) {
              return false;
            }
            return true;
          } catch (_) {
            return false;
          }
        };

        const __isNativeFunction = function(v) {
          if (typeof v !== 'function') return false;
          try {
            return Function.prototype.toString.call(v).indexOf('[native code]') !== -1;
          } catch (_) {
            return true;
          }
        };

        const __isNativeHostObject = function(k, v) {
          if (!v || typeof v !== 'object') return false;
          if (__KNOWN_ENGINE_GLOBALS.has(k)) return false;
          if (Array.isArray(v) || ArrayBuffer.isView(v)) return false;
          if (v instanceof Map || v instanceof Set) return false;
          try {
            const proto = Object.getPrototypeOf(v);
            if (proto === null || proto === Object.prototype) return false;
            const ctor = v.constructor;
            if (typeof ctor === 'function' && ctor !== Object && __isNativeFunction(ctor)) {
              return true;
            }
          } catch (_) {
            return true;
          }
          return false;
        };

        const __setStatus = function(status, detail) {
          try {
            if (__bridge && typeof __bridge.updateScanStatus === 'function') {
              __bridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __setBatchProgress = function(currentBatch, totalBatches, itemStart, itemEnd, totalItems, foundCount, isActive, isPaused) {
          try {
            if (__bridge && typeof __bridge.updateBatchProgress === 'function') {
              __bridge.updateBatchProgress(
                currentBatch | 0,
                totalBatches | 0,
                itemStart | 0,
                itemEnd | 0,
                totalItems | 0,
                foundCount | 0,
                Boolean(isActive),
                Boolean(isPaused)
              );
            }
          } catch (_) {}
        };

        const __isSpecialHostObject = function(v) {
          if (!v || typeof v !== 'object') return false;
          try {
            if (v === window || v === document || v === globalThis) return true;
            if (typeof Window !== 'undefined' && v instanceof Window) return true;
            if (typeof Document !== 'undefined' && v instanceof Document) return true;
            if (typeof Node !== 'undefined' && v instanceof Node) return true;
            if (typeof HTMLCollection !== 'undefined' && v instanceof HTMLCollection) return true;
            if (typeof NodeList !== 'undefined' && v instanceof NodeList) return true;
            if (typeof CSSStyleDeclaration !== 'undefined' && v instanceof CSSStyleDeclaration) return true;
            if (typeof WebGL2RenderingContext !== 'undefined' && v instanceof WebGL2RenderingContext) return true;
            if (typeof WebGLRenderingContext !== 'undefined' && v instanceof WebGLRenderingContext) return true;
            if (typeof CanvasRenderingContext2D !== 'undefined' && v instanceof CanvasRenderingContext2D) return true;
            if (typeof AudioContext !== 'undefined' && v instanceof AudioContext) return true;
          } catch (_) {
            return true;
          }
          return false;
        };

        const __describeSkippedObject = function(v) {
          try {
            if (v === window || v === globalThis) return '[Window]';
            if (v === document) return '[Document]';
            if (typeof Node !== 'undefined' && v instanceof Node) return '[DOM:' + (v.nodeName || 'Node') + ']';
            if (typeof WebGL2RenderingContext !== 'undefined' && v instanceof WebGL2RenderingContext) return '[Context:WebGL2]';
            if (typeof WebGLRenderingContext !== 'undefined' && v instanceof WebGLRenderingContext) return '[Context:WebGL1]';
            if (typeof CanvasRenderingContext2D !== 'undefined' && v instanceof CanvasRenderingContext2D) return '[Context:Canvas2D]';
            const ctor = v && v.constructor && v.constructor.name ? v.constructor.name : 'HostObject';
            return '[' + ctor + ']';
          } catch (_) {
            return '[Skipped]';
          }
        };

        const __safeSerialize = function(value, indent) {
          const seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
          try {
            const json = JSON.stringify(value, function(key, val) {
              if (val === undefined) return undefined;
              if (val === null) return null;
              const t = typeof val;
              if (t === 'function' || t === 'symbol') return undefined;
              if (t === 'bigint') return val.toString();
              if (t === 'number' && !Number.isFinite(val)) return null;
              if (t === 'object') {
                if (__isSpecialHostObject(val)) return __describeSkippedObject(val);
                if (seen) {
                  if (seen.has(val)) return '[Circular]';
                  seen.add(val);
                }
              }
              return val;
            }, indent || 0);
            if (json && json.length > 14000) return json.slice(0, 14000) + '… [truncated]';
            return json || '{}';
          } catch (e) {
            return JSON.stringify({ serializationError: String(e) });
          }
        };

        const __emit = function(cat, obj) {
          try {
            if (!__bridge || typeof __bridge.logTelemetry !== 'function') return;
            const payload = typeof obj === 'string' ? obj : __safeSerialize(obj, 0);
            __bridge.logTelemetry(cat, payload);
          } catch (_) {}
        };

        const __yieldFrame = function(callback) {
          if (typeof requestAnimationFrame === 'function') {
            let fired = false;
            const guardTimer = setTimeout(function() {
              if (!fired) { fired = true; callback(); }
            }, 32);
            try {
              requestAnimationFrame(function() {
                if (!fired) {
                  fired = true;
                  clearTimeout(guardTimer);
                  setTimeout(callback, 0);
                }
              });
            } catch (_) {
              if (!fired) {
                fired = true;
                clearTimeout(guardTimer);
                setTimeout(callback, 8);
              }
            }
          } else {
            setTimeout(callback, 8);
          }
        };

        /**
         * True async batch yield: gives the browser main thread >= 15ms plus a render frame
         * boundary (guarded by a 24ms timeout fallback) so frames and network packets never stall.
         */
        const __yieldBatchAsync = function(delayMs) {
          const waitMs = (typeof delayMs === 'number' && delayMs >= 0) ? delayMs : 15;
          return new Promise(function(resolve) {
            setTimeout(function() {
              if (typeof requestAnimationFrame === 'function') {
                let settled = false;
                const guard = setTimeout(function() {
                  if (!settled) { settled = true; resolve(); }
                }, 24);
                try {
                  requestAnimationFrame(function() {
                    if (!settled) {
                      settled = true;
                      clearTimeout(guard);
                      resolve();
                    }
                  });
                } catch (_) {
                  if (!settled) {
                    settled = true;
                    clearTimeout(guard);
                    resolve();
                  }
                }
              } else {
                resolve();
              }
            }, waitMs);
          });
        };

        /**
         * O(1)-safe check for whether an object has at least one own enumerable key without
         * calling Object.keys() on massive TypedArrays, ArrayBuffers, or huge Arrays.
         */
        const __hasAnyOwnKey = function(val) {
          if (!val || (typeof val !== 'object' && typeof val !== 'function')) return false;
          try {
            if (ArrayBuffer.isView(val)) return val.byteLength > 0;
            if (typeof ArrayBuffer !== 'undefined' && val instanceof ArrayBuffer) return val.byteLength > 0;
            if (Array.isArray(val)) return val.length > 0;
            if (val instanceof Map || val instanceof Set) return val.size > 0;
            for (const pk in val) {
              if (Object.prototype.hasOwnProperty.call(val, pk)) return true;
            }
          } catch (_) {
            return false;
          }
          return false;
        };

        /**
         * Bounded, stall-free object metadata inspector. Never enumerates all indices of
         * TypedArrays (e.g. WASM HEAPU8) or huge Arrays/Maps, capping key inspection at 48 keys.
         */
        const __safeInspectObjectSummary = function(k, val, isKnown) {
          const t = typeof val;
          let ctor = t;
          let keyCount = 0;
          let previewKeys = [];
          let heuristicBoost = isKnown ? 100 : 0;

          try {
            if (val && val.constructor && val.constructor.name) {
              ctor = String(val.constructor.name);
            }
          } catch (_) {}

          try {
            if (ArrayBuffer.isView(val)) {
              keyCount = typeof val.length === 'number' ? val.length : val.byteLength;
              previewKeys = ['byteLength:' + val.byteLength];
            } else if (typeof ArrayBuffer !== 'undefined' && val instanceof ArrayBuffer) {
              keyCount = val.byteLength;
              previewKeys = ['byteLength:' + val.byteLength];
            } else if (val instanceof Map || val instanceof Set) {
              keyCount = val.size;
              let idx = 0;
              for (const mk of val.keys()) {
                if (idx >= 5) break;
                previewKeys.push(String(mk).slice(0, 24));
                idx++;
              }
            } else if (Array.isArray(val)) {
              keyCount = val.length;
              previewKeys = ['length:' + val.length];
            } else {
              let scanned = 0;
              const MAX_SCAN = 48;
              for (const pk in val) {
                if (!Object.prototype.hasOwnProperty.call(val, pk)) continue;
                scanned++;
                if (previewKeys.length < 5) previewKeys.push(pk);
                if (__HEURISTIC_KEY_RE.test(pk)) heuristicBoost += 20;
                if (scanned >= MAX_SCAN) break;
              }
              keyCount = scanned;
            }
          } catch (_) {}

          if (__HEURISTIC_KEY_RE.test(k)) heuristicBoost += 50;

          return {
            key: k,
            path: 'window.' + k,
            type: t,
            constructor: ctor,
            keyCount: keyCount,
            previewKeys: previewKeys,
            score: heuristicBoost + Math.min(keyCount, 30)
          };
        };

        const __isCustomGlobalCandidate = function(k, val) {
          if (val === undefined || val === null) return false;
          if (/^\d+$/.test(k)) return false;
          if (!__KNOWN_ENGINE_GLOBALS.has(k) && __BUILTIN_BLACKLIST_RE.test(k)) return false;

          const t = typeof val;
          if (t === 'function') {
            if (__isNativeFunction(val)) return false;
            if (__KNOWN_ENGINE_GLOBALS.has(k)) return true;
            return __hasAnyOwnKey(val);
          }

          if (t !== 'object') return false;
          if (__isSpecialHostObject(val)) return false;
          if (__isNativeHostObject(k, val)) return false;

          if (__KNOWN_ENGINE_GLOBALS.has(k)) return true;
          if (ArrayBuffer.isView(val)) return true;
          if (typeof ArrayBuffer !== 'undefined' && val instanceof ArrayBuffer) return val.byteLength > 0;
          if (val instanceof Map || val instanceof Set) return val.size > 0;

          try {
            if (__hasAnyOwnKey(val)) return true;
            const ctor = val.constructor;
            if (typeof ctor === 'function' && ctor !== Object && !__isNativeFunction(ctor)) {
              return true;
            }
          } catch (_) {}
          return false;
        };

        const __filterCustomWindowKeys = function() {
          const rawKeys = Object.getOwnPropertyNames(window);
          const filtered = [];
          for (let i = 0; i < rawKeys.length; i++) {
            const k = rawKeys[i];
            if (/^\d+$/.test(k)) continue;
            if (__KNOWN_ENGINE_GLOBALS.has(k)) {
              filtered.push(k);
              continue;
            }
            if (__BUILTIN_BLACKLIST_RE.test(k)) continue;
            if (__STANDARD_EXACT.has(k)) continue;
            if (__WEB_API_PREFIX_RE.test(k)) continue;
            if (__WEB_API_SUFFIX_RE.test(k)) continue;
            filtered.push(k);
          }
          return { totalWindowKeys: rawKeys.length, customKeys: filtered };
        };

        const __isShortNumericVector = function(v) {
          if (!v || typeof v !== 'object') return false;
          if (!(Array.isArray(v) || (ArrayBuffer.isView(v) && !(v instanceof DataView)))) return false;
          if (v.length < 2 || v.length > 4) return false;
          for (let i = 0; i < v.length; i++) {
            if (typeof v[i] !== 'number' || !Number.isFinite(v[i])) return false;
          }
          return true;
        };

        const __parsePathTokens = function(path) {
          if (!path || typeof path !== 'string') return [];
          const trimmed = path.trim().replace(/^(window|globalThis|self)\./, '');
          const rawTokens = trimmed.match(/[^.\[\]'"]+/g) || [];
          const out = [];
          for (let i = 0; i < rawTokens.length; i++) {
            const tok = rawTokens[i].trim();
            if (tok.length > 0) out.push(tok);
          }
          return out;
        };

        const __resolvePath = function(path) {
          try {
            const tokens = __parsePathTokens(path);
            if (tokens.length === 0) return undefined;
            let cur = window;
            for (let i = 0; i < tokens.length; i++) {
              if (cur === null || cur === undefined) return undefined;
              cur = cur[tokens[i]];
            }
            return cur;
          } catch (_) {
            return undefined;
          }
        };

        const __buildPathFromTokens = function(tokens) {
          let out = 'window';
          for (let i = 0; i < tokens.length; i++) {
            const t = tokens[i];
            if (/^\d+$/.test(t)) out += '[' + t + ']';
            else out += '.' + t;
          }
          return out;
        };

        const __extractVec3 = function(obj) {
          if (!obj || typeof obj !== 'object' || __isSpecialHostObject(obj)) return null;
          try {
            if (typeof obj.x === 'number' && Number.isFinite(obj.x) && typeof obj.y === 'number' && Number.isFinite(obj.y)) {
              return { x: obj.x, y: obj.y, z: (typeof obj.z === 'number' && Number.isFinite(obj.z)) ? obj.z : 0 };
            }
            if (typeof obj.x === 'number' && Number.isFinite(obj.x) && typeof obj.z === 'number' && Number.isFinite(obj.z)) {
              return { x: obj.x, y: (typeof obj.y === 'number' && Number.isFinite(obj.y)) ? obj.y : 0, z: obj.z };
            }
            if (__isShortNumericVector(obj)) {
              return { x: obj[0], y: obj[1], z: obj.length >= 3 ? obj[2] : 0 };
            }
            const nestedKeys = ['position', 'pos', 'coords', 'coord', 'location', 'loc', 'transform'];
            for (let i = 0; i < nestedKeys.length; i++) {
              const sub = obj[nestedKeys[i]];
              if (!sub || typeof sub !== 'object' || __isSpecialHostObject(sub)) continue;
              if (typeof sub.x === 'number' && Number.isFinite(sub.x) && typeof sub.y === 'number' && Number.isFinite(sub.y)) {
                return { x: sub.x, y: sub.y, z: (typeof sub.z === 'number' && Number.isFinite(sub.z)) ? sub.z : 0 };
              }
              if (__isShortNumericVector(sub)) {
                return { x: sub[0], y: sub[1], z: sub.length >= 3 ? sub[2] : 0 };
              }
            }
          } catch (_) {}
          return null;
        };

        /**
         * Reads live coordinates directly from the stored object reference (`liveRef.parentObject[propName]`
         * and `liveRef.refObject[xKey/yKey/zKey]`) rather than a primitive copy, automatically following
         * both in-place property mutations and nested vector object replacements.
         */
        const __readLiveRefVec = function() {
          const ref = __state.liveRef;
          if (!ref) return null;
          try {
            let liveTarget = ref.refObject || ref.targetObject;

            // Follow direct nested property reference `parentObject[propName]` when parent is a named container
            if (ref.parentObject && ref.propName !== null && ref.propName !== undefined && !Array.isArray(ref.parentObject)) {
              const nested = ref.parentObject[ref.propName];
              if (typeof nested === 'number' && Number.isFinite(nested) && ref.refObject === ref.parentObject) {
                return { x: nested, y: 0, z: 0 };
              }
              if (nested && typeof nested === 'object' && !__isSpecialHostObject(nested)) {
                liveTarget = nested;
                ref.refObject = nested;
                ref.targetObject = nested;
              }
            }

            // Fallback path re-resolution only if direct object reference is missing
            if (!liveTarget || typeof liveTarget !== 'object') {
              const fresh = __resolvePath(ref.path);
              if (fresh && typeof fresh === 'object' && !__isSpecialHostObject(fresh)) {
                liveTarget = fresh;
                ref.refObject = fresh;
                ref.targetObject = fresh;
              } else if (typeof fresh === 'number' && Number.isFinite(fresh)) {
                return { x: fresh, y: 0, z: 0 };
              }
            }

            if (liveTarget && typeof liveTarget === 'object') {
              const rx = ref.xKey !== null && ref.xKey !== undefined ? liveTarget[ref.xKey] : undefined;
              const ry = ref.yKey !== null && ref.yKey !== undefined ? liveTarget[ref.yKey] : undefined;
              const rz = ref.zKey !== null && ref.zKey !== undefined ? liveTarget[ref.zKey] : undefined;
              if ((typeof rx === 'number' && Number.isFinite(rx)) ||
                  (typeof ry === 'number' && Number.isFinite(ry)) ||
                  (typeof rz === 'number' && Number.isFinite(rz))) {
                return {
                  x: (typeof rx === 'number' && Number.isFinite(rx)) ? rx : 0,
                  y: (typeof ry === 'number' && Number.isFinite(ry)) ? ry : 0,
                  z: (typeof rz === 'number' && Number.isFinite(rz)) ? rz : 0
                };
              }
              const vec = __extractVec3(liveTarget);
              if (vec) return vec;
            }
          } catch (_) {}
          return null;
        };

        const __stopLivePlayerPoll = function() {
          try {
            if (__state.livePollId !== null) {
              clearInterval(__state.livePollId);
              __state.livePollId = null;
            }
          } catch (_) {}
        };

        /**
         * Starts continuous 200ms background polling over the direct JS object reference,
         * pushing live X/Y/Z updates through TelemetryBridge.updatePlayerCoords().
         */
        const __startLivePlayerPoll = function() {
          __stopLivePlayerPoll();
          try {
            const pushNow = function() {
              try {
                const v = __readLiveRefVec();
                const activePath = (__state.liveRef && __state.liveRef.path) || __state.discoveredPlayerPath;
                if (v && activePath && __bridge && typeof __bridge.updatePlayerCoords === 'function') {
                  __bridge.updatePlayerCoords(activePath, v.x, v.y, v.z);
                }
              } catch (_) {}
            };
            pushNow();
            __state.livePollId = setInterval(pushNow, __LIVE_POLL_INTERVAL_MS);
          } catch (_) {}
        };

        /**
         * Resolves a path into `{ parentObject, propName }` so we always hold a direct JS reference
         * to the nested property (`parentObject[propName]`) rather than a primitive copy.
         */
        const __resolveParentRef = function(path) {
          const tokens = __parsePathTokens(path);
          if (tokens.length === 0) return { parentObject: null, propName: null };
          if (tokens.length === 1) return { parentObject: window, propName: tokens[0] };
          const parentPath = __buildPathFromTokens(tokens.slice(0, tokens.length - 1));
          const parentObj = __resolvePath(parentPath);
          const propName = tokens[tokens.length - 1];
          return {
            parentObject: (parentObj && typeof parentObj === 'object') ? parentObj : null,
            propName: propName
          };
        };

        /**
         * Binds a direct JS object reference (`refObject`, `parentObject[propName]`) + exact
         * property keys (`xKey`, `yKey`, `zKey`) for continuous live 200ms polling.
         */
        const __bindLiveTargetRef = function(containerPath, explicitKeys) {
          const obj = __resolvePath(containerPath);
          if (obj && typeof obj === 'object' && !__isSpecialHostObject(obj)) {
            let xKey = explicitKeys && explicitKeys.x !== undefined ? explicitKeys.x : null;
            let yKey = explicitKeys && explicitKeys.y !== undefined ? explicitKeys.y : null;
            let zKey = explicitKeys && explicitKeys.z !== undefined ? explicitKeys.z : null;

            if (xKey === null && yKey === null && zKey === null) {
              if (typeof obj.x === 'number') xKey = 'x';
              if (typeof obj.y === 'number') yKey = 'y';
              if (typeof obj.z === 'number') zKey = 'z';
              if (xKey === null && __isShortNumericVector(obj)) {
                xKey = 0;
                yKey = 1;
                zKey = obj.length >= 3 ? 2 : null;
              }
            }
            const parentInfo = __resolveParentRef(containerPath);
            __state.discoveredPlayerPath = containerPath;
            __state.liveRef = {
              path: containerPath,
              parentObject: parentInfo.parentObject,
              propName: parentInfo.propName,
              refObject: obj,
              targetObject: obj,
              xKey: xKey,
              yKey: yKey,
              zKey: zKey
            };
            __startLivePlayerPoll();
            return __state.liveRef;
          }

          // If user pinned a numeric leaf path (e.g. window.game.player.pos.x), bind directly to parentObject[leafKey]
          const tokens = __parsePathTokens(containerPath);
          if (tokens.length >= 2) {
            const leafKey = tokens[tokens.length - 1];
            const parentPath = __buildPathFromTokens(tokens.slice(0, tokens.length - 1));
            const parentObj = __resolvePath(parentPath);
            if (parentObj && typeof parentObj === 'object' && !__isSpecialHostObject(parentObj)) {
              const xKey = typeof parentObj.x === 'number' ? 'x' : leafKey;
              const yKey = typeof parentObj.y === 'number' ? 'y' : null;
              const zKey = typeof parentObj.z === 'number' ? 'z' : null;
              const grandParentInfo = __resolveParentRef(parentPath);
              __state.discoveredPlayerPath = parentPath;
              __state.liveRef = {
                path: parentPath,
                parentObject: grandParentInfo.parentObject || parentObj,
                propName: grandParentInfo.parentObject ? grandParentInfo.propName : leafKey,
                refObject: parentObj,
                targetObject: parentObj,
                xKey: xKey,
                yKey: yKey,
                zKey: zKey
              };
              __startLivePlayerPoll();
              return __state.liveRef;
            }
          }
          return null;
        };
    """

    /**
     * Step 1: Non-Blocking Yielding Batched Global Variable Inspection (100 items per batch)
     * with Immediate Targeted Game Namespace Auto-Detection.
     *
     * 1) Immediately probes common game engine roots (window.game, window.app, window.engine,
     *    window.Phaser, window.PIXI, window.BABYLON, window.Module, etc.) and injects matches
     *    straight into the Staging buffer.
     * 2) Iterates all `Object.getOwnPropertyNames(window)` keys in 100-item batches using a true
     *    `async` loop with `await new Promise(resolve => setTimeout(resolve, 15))` + rAF yielding,
     *    per-property try/catch guards, and O(1) TypedArray/object safety so it finishes every
     *    batch from 1/N to N/N without hanging or halting the game engine.
     */
    fun buildInspectGlobalScopeScript(): String = """
        (async function() {
          try {
            $SHARED_RUNTIME
            const BATCH_SIZE = 100;
            const BATCH_YIELD_MS = 15;

            // Cancel any previously running batched scan cleanly
            if (__state.scopeBatch) {
              __state.scopeBatch.active = false;
              __state.scopeBatch.paused = false;
              if (__state.scopeBatch.timerId !== null) {
                clearTimeout(__state.scopeBatch.timerId);
                __state.scopeBatch.timerId = null;
              }
              if (typeof __state.scopeBatch.resumeResolver === 'function') {
                try { __state.scopeBatch.resumeResolver(); } catch (_) {}
                __state.scopeBatch.resumeResolver = null;
              }
            }

            const mySeq = ++__state.scanSeq;
            const rawKeys = Object.getOwnPropertyNames(window);
            const totalItems = rawKeys.length;
            const totalBatches = Math.max(1, Math.ceil(totalItems / BATCH_SIZE));

            const job = {
              seq: mySeq,
              active: true,
              paused: false,
              rawKeys: rawKeys,
              cursor: 0,
              batchIndex: 0,
              totalBatches: totalBatches,
              batchSize: BATCH_SIZE,
              totalItems: totalItems,
              startTimeMs: performance.now(),
              customObjects: [],
              detectedNamespaces: [],
              globalContexts: [],
              timerId: null,
              resumeResolver: null
            };
            __state.scopeBatch = job;

            const seenKeys = new Set();

            // Phase A: Immediate Targeted Game Namespace Auto-Detection
            const autoRootsFound = [];
            for (let ri = 0; ri < __TARGETED_ENGINE_ROOTS.length; ri++) {
              const rk = __TARGETED_ENGINE_ROOTS[ri];
              if (seenKeys.has(rk)) continue;
              try {
                if (!(rk in window)) continue;
                const rv = window[rk];
                if (rv === undefined || rv === null) continue;
                const rt = typeof rv;
                if (rt !== 'object' && rt !== 'function') continue;
                if (rt === 'object' && __isSpecialHostObject(rv)) continue;
                if (rt === 'function' && __isNativeFunction(rv)) continue;

                const entry = __safeInspectObjectSummary(rk, rv, true);
                entry.score += 150;
                entry.autoDetected = true;
                seenKeys.add(rk);
                job.customObjects.push(entry);
                job.detectedNamespaces.push('window.' + rk);
                autoRootsFound.push(entry);

                // Also stage up to 4 high-signal 1-level children (e.g. window.game.player)
                if (rt === 'object' && !ArrayBuffer.isView(rv) && !Array.isArray(rv)) {
                  let childAdded = 0;
                  for (const ck in rv) {
                    if (childAdded >= 4) break;
                    if (!Object.prototype.hasOwnProperty.call(rv, ck)) continue;
                    if (!__HEURISTIC_KEY_RE.test(ck)) continue;
                    try {
                      const cv = rv[ck];
                      if (cv && typeof cv === 'object' && !__isSpecialHostObject(cv)) {
                        const childEntry = __safeInspectObjectSummary(rk + '.' + ck, cv, true);
                        childEntry.path = 'window.' + rk + '.' + ck;
                        childEntry.autoDetected = true;
                        autoRootsFound.push(childEntry);
                        childAdded++;
                      }
                    } catch (_) {}
                  }
                }
              } catch (_) {}
            }

            if (autoRootsFound.length > 0) {
              __emit('SCOPE', {
                stage: 'GAME_NAMESPACE_DETECTED',
                detectedNamespaces: job.detectedNamespaces,
                detectedEntries: autoRootsFound
              });
            }

            // Phase B: True Async Non-Blocking Yielding Batches (100 items / batch)
            __setStatus('RUNNING', 'Scanning batch 1/' + totalBatches + ' (items 1–' + Math.min(BATCH_SIZE, totalItems) + ')…');
            __setBatchProgress(1, totalBatches, totalItems > 0 ? 1 : 0, Math.min(BATCH_SIZE, totalItems), totalItems, job.customObjects.length, true, false);

            let skippedErrors = 0;

            for (let startIdx = 0; startIdx < totalItems; startIdx += BATCH_SIZE) {
              if (!job.active || job.seq !== __state.scanSeq) return;

              // Suspend cleanly if user tapped Pause
              while (job.paused && job.active && job.seq === __state.scanSeq) {
                await new Promise(function(resolve) {
                  job.resumeResolver = resolve;
                });
                job.resumeResolver = null;
              }
              if (!job.active || job.seq !== __state.scanSeq) return;

              const endIdx = Math.min(startIdx + BATCH_SIZE, totalItems);
              job.batchIndex++;
              const itemStart = totalItems > 0 ? (startIdx + 1) : 0;
              const itemEnd = endIdx;

              const batchLabel = 'Scanning batch ' + job.batchIndex + '/' + job.totalBatches +
                ' (items ' + itemStart + '–' + itemEnd + ')…';
              __setStatus('RUNNING', batchLabel);
              __setBatchProgress(
                job.batchIndex,
                job.totalBatches,
                itemStart,
                itemEnd,
                totalItems,
                job.customObjects.length,
                true,
                false
              );

              const batchFound = [];
              for (let i = startIdx; i < endIdx; i++) {
                try {
                  const k = job.rawKeys[i];
                  if (!k || seenKeys.has(k) || /^\d+$/.test(k)) continue;
                  const isKnown = __KNOWN_ENGINE_GLOBALS.has(k);
                  if (!isKnown) {
                    if (__BUILTIN_BLACKLIST_RE.test(k)) continue;
                    if (__STANDARD_EXACT.has(k)) continue;
                    if (__WEB_API_PREFIX_RE.test(k)) continue;
                    if (__WEB_API_SUFFIX_RE.test(k)) continue;
                  }

                  let val;
                  try { val = window[k]; } catch (_) { skippedErrors++; continue; }
                  if (val === undefined || val === null) continue;
                  const t = typeof val;
                  if (t === 'object' && __isSpecialHostObject(val)) {
                    const desc = __describeSkippedObject(val);
                    if (desc.indexOf('[Context:') === 0) {
                      job.globalContexts.push({ source: 'window.' + k, kind: desc });
                    }
                    continue;
                  }
                  if (!__isCustomGlobalCandidate(k, val)) continue;

                  const entry = __safeInspectObjectSummary(k, val, isKnown);
                  seenKeys.add(k);
                  job.customObjects.push(entry);
                  batchFound.push(entry);
                } catch (_) {
                  skippedErrors++;
                }
              }

              job.cursor = endIdx;

              if (batchFound.length > 0) {
                __emit('SCOPE', {
                  stage: 'GLOBAL_SCOPE_BATCH',
                  batchIndex: job.batchIndex,
                  totalBatches: job.totalBatches,
                  itemStart: itemStart,
                  itemEnd: itemEnd,
                  totalItems: totalItems,
                  foundCount: job.customObjects.length,
                  batchCustomGlobals: batchFound
                });
              }

              __setBatchProgress(
                job.batchIndex,
                job.totalBatches,
                itemStart,
                itemEnd,
                totalItems,
                job.customObjects.length,
                job.cursor < totalItems,
                false
              );

              // True async yield between batches (>=15ms + rAF) so the game engine never stalls
              if (job.cursor < totalItems) {
                await __yieldBatchAsync(BATCH_YIELD_MS);
              }
            }

            if (!job.active || job.seq !== __state.scanSeq) return;

            // Phase C: Safe Finalization (never calls getContext('2d') on active WebGL canvases)
            job.active = false;
            job.paused = false;
            job.customObjects.sort(function(a, b) { return b.score - a.score; });

            const canvases = [];
            let domCanvasCount = 0;
            try {
              const domCanvases = document.querySelectorAll('canvas');
              domCanvasCount = domCanvases.length;
              for (let c = 0; c < domCanvases.length && c < 8; c++) {
                try {
                  const el = domCanvases[c];
                  const rect = el.getBoundingClientRect();
                  const ctxHint = (el.__webgl || el.gl) ? 'WebGL' : 'active';
                  canvases.push({
                    index: c,
                    id: el.id || null,
                    width: el.width,
                    height: el.height,
                    cssWidth: Math.round(rect.width),
                    cssHeight: Math.round(rect.height),
                    context: ctxHint
                  });
                } catch (_) {}
              }
            } catch (_) {}

            const durationMs = Number((performance.now() - job.startTimeMs).toFixed(2));
            __state.customGlobals = job.customObjects.map(function(o) { return o.key; });
            __state.shallowPaths = job.customObjects.map(function(o) { return o.path; });

            __setBatchProgress(
              job.totalBatches,
              job.totalBatches,
              Math.max(1, totalItems - BATCH_SIZE + 1),
              totalItems,
              totalItems,
              job.customObjects.length,
              false,
              false
            );
            __setStatus('COMPLETED', 'Step 1: ' + job.customObjects.length + ' custom globals (' + job.totalBatches + '/' + job.totalBatches + ' batches)');

            __emit('SCOPE', {
              stage: 'GLOBAL_SCOPE_INSPECTED',
              durationMs: durationMs,
              batchesExecuted: job.totalBatches,
              batchSize: BATCH_SIZE,
              yieldDelayMs: BATCH_YIELD_MS,
              totalWindowKeysFiltered: totalItems,
              skippedProtectedKeys: skippedErrors,
              detectedNamespaces: job.detectedNamespaces,
              customGlobalCount: job.customObjects.length,
              customGlobals: job.customObjects.slice(0, 80),
              canvasCount: domCanvasCount,
              canvases: canvases,
              globalContexts: job.globalContexts,
              viewport: {
                width: window.innerWidth,
                height: window.innerHeight,
                dpr: window.devicePixelRatio || 1
              }
            });
          } catch (err) {
            try {
              if (window.__wvDiagState && window.__wvDiagState.scopeBatch) {
                window.__wvDiagState.scopeBatch.active = false;
                window.__wvDiagState.scopeBatch.paused = false;
              }
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Step 1 error');
                window.TelemetryBridge.logTelemetry('ERR', 'Step 1 Scope error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Pauses an active Step 1 batched global scan cleanly between async batches so the developer
     * can inspect or search already-staged candidates without losing scan progress.
     */
    fun buildPauseScopeScanScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const job = __state.scopeBatch;
            if (!job || !job.active || job.paused) return;
            job.paused = true;
            if (job.timerId !== null) {
              clearTimeout(job.timerId);
              job.timerId = null;
            }
            const itemEnd = job.cursor;
            const itemStart = Math.max(1, itemEnd - job.batchSize + 1);
            __setStatus('PAUSED', 'Paused at batch ' + job.batchIndex + '/' + job.totalBatches + ' (items ' + itemStart + '–' + itemEnd + ')');
            __setBatchProgress(job.batchIndex, job.totalBatches, itemStart, itemEnd, job.totalItems, job.customObjects.length, true, true);
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Resumes a paused Step 1 batched global scan by releasing its async pause gate.
     */
    fun buildResumeScopeScanScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const job = __state.scopeBatch;
            if (!job || !job.active || !job.paused) return;
            job.paused = false;
            const nextBatch = Math.min(job.batchIndex + 1, job.totalBatches);
            const itemStart = Math.min(job.cursor + 1, job.totalItems);
            const itemEnd = Math.min(job.cursor + job.batchSize, job.totalItems);
            __setStatus('RUNNING', 'Scanning batch ' + nextBatch + '/' + job.totalBatches + ' (items ' + itemStart + '–' + itemEnd + ')…');
            __setBatchProgress(nextBatch, job.totalBatches, itemStart, itemEnd, job.totalItems, job.customObjects.length, true, false);
            if (typeof job.resumeResolver === 'function') {
              const wake = job.resumeResolver;
              job.resumeResolver = null;
              wake();
            }
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Step 1 Manual Property Inspector: resolves an arbitrary user-entered global/nested path
     * (e.g. `game.player`, `window.AppInstance`, `Module.HEAPF32`) safely without blocking,
     * inspects its type/keys/coordinates, and immediately stages it into the Staging buffer.
     */
    fun buildQueryPropertyPathScript(rawPath: String): String {
        val escapedPath = JSONObject.quote(rawPath.trim())
        return """
        (function() {
          try {
            $SHARED_RUNTIME
            const rawInput = $escapedPath;
            const tokens = __parsePathTokens(rawInput);
            if (tokens.length === 0) {
              __emit('SCOPE', {
                stage: 'MANUAL_PATH_QUERY',
                found: false,
                requestedPath: rawInput,
                normalizedPath: rawInput,
                message: 'Enter a property path such as game.player or window.AppInstance.'
              });
              return;
            }

            const normalizedPath = __buildPathFromTokens(tokens);
            let cur = window;
            let failedAt = null;
            for (let i = 0; i < tokens.length; i++) {
              if (cur === null || cur === undefined) {
                failedAt = tokens.slice(0, i).join('.');
                break;
              }
              try {
                cur = cur[tokens[i]];
              } catch (accessErr) {
                __emit('SCOPE', {
                  stage: 'MANUAL_PATH_QUERY',
                  found: false,
                  requestedPath: rawInput,
                  normalizedPath: normalizedPath,
                  failedSegment: tokens[i],
                  message: 'Property access blocked at "' + tokens[i] + '": ' + String(accessErr)
                });
                return;
              }
              if (cur === undefined) {
                failedAt = __buildPathFromTokens(tokens.slice(0, i + 1));
                break;
              }
            }

            if (cur === undefined || failedAt !== null) {
              __setStatus('IDLE', 'Path not found on window');
              __emit('SCOPE', {
                stage: 'MANUAL_PATH_QUERY',
                found: false,
                requestedPath: rawInput,
                normalizedPath: normalizedPath,
                failedSegment: failedAt || normalizedPath,
                message: 'Undefined at ' + (failedAt || normalizedPath) + ' (may be closure-scoped or not initialized yet).'
              });
              return;
            }

            const t = typeof cur;
            let detail = t;
            let ctor = t;
            let keyCount = 0;
            let previewKeys = [];
            let vecSample = null;
            const childEntries = [];

            if (cur === null) {
              detail = 'null';
            } else if (t === 'number' || t === 'string' || t === 'boolean' || t === 'bigint') {
              detail = t + ' = ' + String(cur).slice(0, 64);
            } else if (t === 'object' && __isSpecialHostObject(cur)) {
              detail = __describeSkippedObject(cur);
            } else {
              const summary = __safeInspectObjectSummary(tokens[tokens.length - 1], cur, true);
              ctor = summary.constructor;
              keyCount = summary.keyCount;
              previewKeys = summary.previewKeys;
              vecSample = __extractVec3(cur);
              if (vecSample) {
                detail = ctor + ' Vec3(' + vecSample.x.toFixed(2) + ', ' + vecSample.y.toFixed(2) + ', ' + vecSample.z.toFixed(2) + ')';
              } else {
                const previewSuffix = previewKeys.length > 0 ? ' • ' + previewKeys.slice(0, 4).join(', ') : '';
                detail = ctor + ' (' + keyCount + ' keys' + previewSuffix + ')';
              }

              // Collect up to 6 shallow child hints for fast inspection
              if (t === 'object' && !ArrayBuffer.isView(cur) && !Array.isArray(cur)) {
                let added = 0;
                for (const ck in cur) {
                  if (added >= 6) break;
                  if (!Object.prototype.hasOwnProperty.call(cur, ck)) continue;
                  try {
                    const cv = cur[ck];
                    if (cv && typeof cv === 'object' && !__isSpecialHostObject(cv) && __HEURISTIC_KEY_RE.test(ck)) {
                      const cs = __safeInspectObjectSummary(ck, cv, false);
                      childEntries.push({
                        path: normalizedPath + '.' + ck,
                        detail: cs.constructor + ' (' + cs.keyCount + ' keys)'
                      });
                      added++;
                    }
                  } catch (_) {}
                }
              }
            }

            // Register root global so Steps 2A/2B include it automatically
            const rootKey = tokens[0];
            if (__state.customGlobals.indexOf(rootKey) === -1) {
              __state.customGlobals.unshift(rootKey);
            }
            if (__state.shallowPaths.indexOf(normalizedPath) === -1) {
              __state.shallowPaths.unshift(normalizedPath);
            }

            __setStatus('COMPLETED', 'Staged ' + normalizedPath);
            __emit('SCOPE', {
              stage: 'MANUAL_PATH_QUERY',
              found: true,
              requestedPath: rawInput,
              normalizedPath: normalizedPath,
              rootKey: rootKey,
              type: t,
              constructor: ctor,
              keyCount: keyCount,
              previewKeys: previewKeys,
              vec3: vecSample,
              detail: detail,
              childEntries: childEntries
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Query Path failed');
                window.TelemetryBridge.logTelemetry('ERR', 'Manual Path Query error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 2A: Ultra-fast (<5ms), freeze-proof shallow baseline capture.
     *
     * - Explicitly excludes WebAssembly heap/table structures (`HEAP8`, `HEAPU8`, `HEAP16`,
     *   `HEAPU16`, `HEAP32`, `HEAPU32`, `HEAPF32`, `HEAPF64`, `HEAP64`, `HEAPU64`,
     *   `wasmTableMirror`, `wasmTable`, `wasmMemory`) and ALL TypedArrays, ArrayBuffers,
     *   raw byte arrays, and WebGL/rendering buffers.
     * - Collects strictly standard JS primitive finite numbers from top-level and 1-level
     *   nested plain/game objects using bounded `for..in` enumeration (never `Object.keys` on
     *   large buffers) with a strict <5ms execution cap.
     */
    fun buildCaptureBaselineScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            const totalStartMs = performance.now();
            __setStatus('RUNNING', 'Step 2A: Capturing standing baseline…');

            const baseline = Object.create(null);
            const candidateContainers = [];
            const seenContainers = new Set();
            const visited = new WeakSet();
            const queue = [];

            let skippedExcluded = 0;
            const MAX_ROOTS = 24;
            const MAX_DEPTH = 2;
            const MAX_METRICS = 600;
            const MAX_OBJECTS = 120;
            const MAX_KEYS_PER_OBJ = 48;
            const MAX_TOTAL_MS = 4.5;

            const enqueueRoot = function(path, obj) {
              if (!path || __isExcludedWasmKey(path)) { skippedExcluded++; return; }
              if (!__isSafeBaselineObject(obj)) { skippedExcluded++; return; }
              if (visited.has(obj)) return;
              queue.push({ obj: obj, path: path, depth: 1 });
            };

            // 1. Prioritize any manually queried / discovered candidate paths first
            if (Array.isArray(__state.shallowPaths)) {
              for (let si = 0; si < __state.shallowPaths.length && queue.length < MAX_ROOTS; si++) {
                const sp = __state.shallowPaths[si];
                if (!sp || __isExcludedWasmKey(sp)) { skippedExcluded++; continue; }
                const sv = __resolvePath(sp);
                enqueueRoot(sp, sv);
              }
            }

            // 2. Add filtered custom global roots (excluding WASM heaps & TypedArrays)
            const rootKeys = Array.isArray(__state.customGlobals) && __state.customGlobals.length > 0
              ? __state.customGlobals.slice(0, MAX_ROOTS)
              : __filterCustomWindowKeys().customKeys.slice(0, MAX_ROOTS);

            for (let i = 0; i < rootKeys.length && queue.length < MAX_ROOTS; i++) {
              const k = rootKeys[i];
              if (!k || __isExcludedWasmKey(k)) { skippedExcluded++; continue; }
              let v;
              try { v = window[k]; } catch (_) { continue; }
              if (!__isSafeBaselineObject(v)) { skippedExcluded++; continue; }
              if (__isCustomGlobalCandidate(k, v)) {
                enqueueRoot('window.' + k, v);
              }
            }

            let metricCount = 0;
            let objectsScanned = 0;
            let chunksExecuted = 0;

            const recordNum = function(path, val) {
              if (metricCount >= MAX_METRICS) return;
              if (typeof val === 'number' && Number.isFinite(val)) {
                baseline[path] = val;
                metricCount++;
              }
            };

            const processChunk = function() {
              try {
                if (mySeq !== __state.scanSeq) return;
                chunksExecuted++;
                const sliceStart = performance.now();

                while (
                  queue.length > 0 &&
                  metricCount < MAX_METRICS &&
                  objectsScanned < MAX_OBJECTS &&
                  (performance.now() - sliceStart) < MAX_TOTAL_MS &&
                  (performance.now() - totalStartMs) < MAX_TOTAL_MS
                ) {
                  const item = queue.shift();
                  const obj = item.obj;
                  if (!__isSafeBaselineObject(obj) || visited.has(obj)) {
                    skippedExcluded++;
                    continue;
                  }
                  visited.add(obj);
                  objectsScanned++;

                  // Record Vec3-like plain coordinate container if top-level numeric x/y/z present
                  try {
                    if (
                      typeof obj.x === 'number' && Number.isFinite(obj.x) &&
                      (typeof obj.y === 'number' && Number.isFinite(obj.y) ||
                       typeof obj.z === 'number' && Number.isFinite(obj.z)) &&
                      !seenContainers.has(item.path) && candidateContainers.length < 20
                    ) {
                      seenContainers.add(item.path);
                      const vy = (typeof obj.y === 'number' && Number.isFinite(obj.y)) ? obj.y : 0;
                      const vz = (typeof obj.z === 'number' && Number.isFinite(obj.z)) ? obj.z : 0;
                      candidateContainers.push({
                        path: item.path,
                        detail: 'Vec3(' + obj.x.toFixed(1) + ', ' + vy.toFixed(1) + ', ' + vz.toFixed(1) + ')'
                      });
                    }
                  } catch (_) {}

                  // Bounded own-property iteration (never allocates Object.keys() on huge objects)
                  let inspectedKeys = 0;
                  try {
                    for (const k in obj) {
                      if (inspectedKeys >= MAX_KEYS_PER_OBJ || metricCount >= MAX_METRICS) break;
                      if ((performance.now() - totalStartMs) >= MAX_TOTAL_MS) break;
                      if (!Object.prototype.hasOwnProperty.call(obj, k)) continue;
                      if (k.indexOf('__wvDiag') === 0 || /^\d+$/.test(k)) continue;

                      // Explicit exclusion of WebAssembly heaps & tables by key name
                      if (__isExcludedWasmKey(k)) {
                        skippedExcluded++;
                        continue;
                      }
                      inspectedKeys++;

                      let v;
                      try { v = obj[k]; } catch (_) { continue; }
                      if (v === null || v === undefined) continue;
                      const nextPath = item.path + '.' + k;

                      if (typeof v === 'number') {
                        if (Number.isFinite(v)) {
                          recordNum(nextPath, v);
                        }
                      } else if (typeof v === 'object') {
                        // Never descend into TypedArrays, ArrayBuffers, raw arrays, WASM memory, or rendering buffers
                        if (!__isSafeBaselineObject(v)) {
                          skippedExcluded++;
                          continue;
                        }
                        if (item.depth < MAX_DEPTH && __HEURISTIC_KEY_RE.test(k)) {
                          queue.push({ obj: v, path: nextPath, depth: item.depth + 1 });
                        }
                      }
                    }
                  } catch (_) {}
                }

                const durationMs = Number((performance.now() - totalStartMs).toFixed(2));
                __state.baseline = baseline;
                __state.baselineTimeMs = performance.now();
                __setStatus('COMPLETED', '2A Captured (' + metricCount + ' nums in ' + durationMs + 'ms)');
                __emit('DELTA', {
                  stage: 'BASELINE_CAPTURED',
                  durationMs: durationMs,
                  chunksExecuted: chunksExecuted,
                  rootObjectCount: rootKeys.length,
                  objectsScanned: objectsScanned,
                  metricCount: metricCount,
                  skippedBinaryOrWasm: skippedExcluded,
                  candidateContainers: candidateContainers
                });
              } catch (chunkErr) {
                __setStatus('ERROR', 'Step 2A error');
                __emit('ERR', 'Step 2A Baseline error: ' + String(chunkErr));
              }
            };

            __yieldFrame(processChunk);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Step 2A error');
                window.TelemetryBridge.logTelemetry('ERR', 'Step 2A Baseline error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 2B: Isolates the moving player coordinate container from the standing baseline,
     * explicitly skipping any WebAssembly heap/table keys, TypedArrays, or ArrayBuffers,
     * binds the direct JS object reference (`parentObject[propName]` + `refObject`), and
     * launches continuous 200 ms `setInterval` live coordinate polling.
     */
    fun buildComputeDeltaScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            if (!__state.baseline) {
              __setStatus('IDLE', 'Run Step 2A while standing still first');
              __emit('DELTA', {
                stage: 'BASELINE_MISSING',
                message: 'No standing baseline found. Stand still in-game and tap 2A first.'
              });
              return;
            }

            __setStatus('RUNNING', 'Step 2B: Isolating live reference…');
            const baseline = __state.baseline;
            const keys = Object.keys(baseline);
            const changed = [];
            let cursor = 0;
            let chunksExecuted = 0;
            let skippedExcluded = 0;

            const processChunk = function() {
              try {
                if (mySeq !== __state.scanSeq) return;
                chunksExecuted++;
                const sliceStart = performance.now();

                while (cursor < keys.length && (performance.now() - sliceStart) < __CHUNK_BUDGET_MS) {
                  const p = keys[cursor++];
                  if (__isExcludedWasmKey(p)) {
                    skippedExcluded++;
                    continue;
                  }

                  const baseVal = baseline[p];
                  if (typeof baseVal !== 'number' || !Number.isFinite(baseVal)) continue;

                  const tokens = __parsePathTokens(p);
                  if (tokens.length === 0) continue;
                  let hasExcludedSegment = false;
                  for (let ti = 0; ti < tokens.length; ti++) {
                    if (__isExcludedWasmKey(tokens[ti])) {
                      hasExcludedSegment = true;
                      break;
                    }
                  }
                  if (hasExcludedSegment) {
                    skippedExcluded++;
                    continue;
                  }

                  const leaf = tokens[tokens.length - 1];
                  const parentPath = tokens.length > 1
                    ? __buildPathFromTokens(tokens.slice(0, tokens.length - 1))
                    : 'window';

                  // Ensure parent container is a safe plain/game object (never TypedArray/WASM heap)
                  let parentObj = window;
                  if (tokens.length > 1) {
                    parentObj = __resolvePath(parentPath);
                    if (!__isSafeBaselineObject(parentObj)) {
                      skippedExcluded++;
                      continue;
                    }
                  }

                  let curVal;
                  try { curVal = parentObj[leaf]; } catch (_) { continue; }
                  if (typeof curVal !== 'number' || !Number.isFinite(curVal)) continue;

                  const diff = curVal - baseVal;
                  const absDiff = Math.abs(diff);
                  if (absDiff < 0.0001 || absDiff > 10000) continue;
                  if (Math.abs(curVal) > 1e11) continue;
                  if (__TIME_KEY_RE.test(p)) continue;

                  const score =
                    (__COORD_AXIS_RE.test(p) ? 120 : 0) +
                    (__HEURISTIC_KEY_RE.test(p) ? 60 : 0) +
                    (absDiff <= 500 ? 25 : 0);

                  changed.push({
                    path: p,
                    parentPath: parentPath,
                    leaf: leaf,
                    baseline: Number(baseVal.toFixed(4)),
                    current: Number(curVal.toFixed(4)),
                    delta: Number(diff.toFixed(4)),
                    score: score
                  });
                }

                if (cursor < keys.length) {
                  __setStatus('RUNNING', 'Step 2B: ' + cursor + '/' + keys.length + ' checked…');
                  __yieldFrame(processChunk);
                } else {
                  const groups = Object.create(null);
                  for (let i = 0; i < changed.length; i++) {
                    const c = changed[i];
                    if (!groups[c.parentPath]) {
                      groups[c.parentPath] = { parentPath: c.parentPath, items: [], score: 0 };
                    }
                    const g = groups[c.parentPath];
                    g.items.push(c);
                    g.score += c.score;
                  }

                  const rankedGroups = Object.keys(groups).map(function(gp) {
                    const g = groups[gp];
                    const multiAxisBonus = g.items.length >= 2 && g.items.length <= 4 ? 180 : 0;
                    const playerHintBonus = /(player|hero|avatar|self|local|char|pos|position|coord)/i.test(gp) ? 100 : 0;
                    return {
                      parentPath: g.parentPath,
                      items: g.items,
                      totalScore: g.score + multiAxisBonus + playerHintBonus
                    };
                  });
                  rankedGroups.sort(function(a, b) { return b.totalScore - a.totalScore; });

                  changed.sort(function(a, b) {
                    if (b.score !== a.score) return b.score - a.score;
                    return Math.abs(a.delta) - Math.abs(b.delta);
                  });

                  const topDeltas = changed.slice(0, 20).map(function(item) {
                    return {
                      path: item.path,
                      baseline: item.baseline,
                      current: item.current,
                      delta: item.delta
                    };
                  });

                  let discoveredPlayerPath = null;
                  let propertyKeysLabel = '';
                  let liveSample = null;

                  if (rankedGroups.length > 0) {
                    const best = rankedGroups[0];
                    discoveredPlayerPath = best.parentPath;
                    const axisKeys = { x: null, y: null, z: null };
                    for (let i = 0; i < best.items.length; i++) {
                      const it = best.items[i];
                      const lf = it.leaf.toLowerCase();
                      if (lf === 'x' || lf === 'px' || lf === 'left') axisKeys.x = it.leaf;
                      else if (lf === 'y' || lf === 'py' || lf === 'top') axisKeys.y = it.leaf;
                      else if (lf === 'z' || lf === 'pz') axisKeys.z = it.leaf;
                    }
                    if (axisKeys.x === null && best.items[0]) axisKeys.x = best.items[0].leaf;
                    if (axisKeys.y === null && best.items[1]) axisKeys.y = best.items[1].leaf;
                    if (axisKeys.z === null && best.items[2]) axisKeys.z = best.items[2].leaf;

                    // Bind static third axis (e.g. y/z when walking on flat ground) from the live plain object
                    const containerObj = __resolvePath(discoveredPlayerPath);
                    if (__isSafeBaselineObject(containerObj)) {
                      if (axisKeys.x === null && typeof containerObj.x === 'number') axisKeys.x = 'x';
                      if (axisKeys.y === null && typeof containerObj.y === 'number') axisKeys.y = 'y';
                      if (axisKeys.z === null && typeof containerObj.z === 'number') axisKeys.z = 'z';
                    }

                    const bound = __bindLiveTargetRef(discoveredPlayerPath, axisKeys);
                    if (bound) {
                      const kList = [bound.xKey, bound.yKey, bound.zKey].filter(function(k) { return k !== null && k !== undefined; });
                      propertyKeysLabel = kList.join(', ');
                      liveSample = __readLiveRefVec();
                    }
                  }

                  __state.isolatedPaths = topDeltas;
                  const elapsedMs = Math.round(performance.now() - (__state.baselineTimeMs || performance.now()));
                  __setStatus(
                    discoveredPlayerPath ? 'COMPLETED' : 'IDLE',
                    discoveredPlayerPath ? ('Live 200ms: ' + discoveredPlayerPath) : 'No movement detected'
                  );

                  __emit('DELTA', {
                    stage: 'DELTA_COMPUTED',
                    chunksExecuted: chunksExecuted,
                    elapsedMs: elapsedMs,
                    comparedMetrics: keys.length,
                    skippedBinaryOrWasm: skippedExcluded,
                    totalChanged: changed.length,
                    discoveredPlayerPath: discoveredPlayerPath,
                    propertyKeys: propertyKeysLabel,
                    pollingIntervalMs: __LIVE_POLL_INTERVAL_MS,
                    liveSample: liveSample,
                    deltas: topDeltas
                  });
                }
              } catch (chunkErr) {
                __setStatus('ERROR', 'Step 2B error');
                __emit('ERR', 'Step 2B Delta error: ' + String(chunkErr));
              }
            };

            __yieldFrame(processChunk);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Step 2B error');
                window.TelemetryBridge.logTelemetry('ERR', 'Step 2B Delta error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Binds a user-selected candidate path from the Staging Buffer directly as the live 200ms
     * coordinate reference so developers can test alternate pinned targets on the fly.
     */
    fun buildLockCandidateRefScript(candidatePath: String): String {
        val pathLiteral = JSONObject.quote(candidatePath)
        return """
        (function() {
          try {
            $SHARED_RUNTIME
            const targetPath = $pathLiteral;
            const bound = __bindLiveTargetRef(targetPath, null);
            if (!bound) {
              __setStatus('ERROR', 'Cannot bind live reference');
              __emit('DELTA', {
                stage: 'LIVE_REF_FAILED',
                path: targetPath,
                message: 'Target path could not be resolved to an active object reference.'
              });
              return;
            }
            const sample = __readLiveRefVec();
            const kList = [bound.xKey, bound.yKey, bound.zKey].filter(function(k) { return k !== null && k !== undefined; });
            __setStatus('COMPLETED', 'Live 200ms: ' + bound.path);
            __emit('DELTA', {
              stage: 'LIVE_REF_LOCKED',
              discoveredPlayerPath: bound.path,
              propertyKeys: kList.join(', '),
              pollingIntervalMs: __LIVE_POLL_INTERVAL_MS,
              liveSample: sample
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Live ref bind error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 3: Player-Anchored Entity Discovery.
     * Resolves `discoveredPlayerPath` (or active `liveRef`), climbs 1-3 parent levels up,
     * scans sibling Arrays/Maps/Sets/dictionaries for nearby coordinate-bearing entities,
     * and calculates 2D canvas projections (`screenX`, `screenY`).
     */
    fun buildScanEntitiesScript(anchorPlayerPath: String?): String {
        val anchorLiteral = if (anchorPlayerPath.isNullOrBlank()) "null" else JSONObject.quote(anchorPlayerPath)
        return """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            const anchorPath = $anchorLiteral || (__state.liveRef && __state.liveRef.path) || __state.discoveredPlayerPath;

            if (!anchorPath) {
              __setStatus('IDLE', 'Complete Step 2B first');
              __emit('ENTITY', {
                stage: 'ANCHOR_MISSING',
                message: 'No player coordinate path isolated yet. Complete Step 2A and 2B first.'
              });
              return;
            }

            __state.discoveredPlayerPath = anchorPath;
            __setStatus('RUNNING', 'Step 3: Climbing parent tree from player…');

            const playerObj = (__state.liveRef && __state.liveRef.targetObject) || __resolvePath(anchorPath);
            const playerVec = __readLiveRefVec() || __extractVec3(playerObj) || { x: 0, y: 0, z: 0 };
            const anchorTokens = __parsePathTokens(anchorPath);

            const ancestors = [];
            for (let climb = 1; climb <= 3; climb++) {
              if (anchorTokens.length - climb < 1) break;
              const parentTokens = anchorTokens.slice(0, anchorTokens.length - climb);
              const parentPath = __buildPathFromTokens(parentTokens);
              const parentObj = __resolvePath(parentPath);
              if (parentObj && typeof parentObj === 'object' && !__isSpecialHostObject(parentObj)) {
                ancestors.push({ climb: climb, path: parentPath, obj: parentObj });
              }
            }

            const canvases = document.querySelectorAll('canvas');
            let primaryCanvas = null;
            let bestArea = -1;
            for (let i = 0; i < canvases.length; i++) {
              const r = canvases[i].getBoundingClientRect();
              const area = r.width * r.height;
              if (area > bestArea) {
                bestArea = area;
                primaryCanvas = canvases[i];
              }
            }
            const vpWidth = primaryCanvas ? (primaryCanvas.clientWidth || primaryCanvas.width || window.innerWidth) : window.innerWidth;
            const vpHeight = primaryCanvas ? (primaryCanvas.clientHeight || primaryCanvas.height || window.innerHeight) : window.innerHeight;

            const isMat4 = function(v) {
              if (!v || typeof v !== 'object') return false;
              const arr = Array.isArray(v.elements) || ArrayBuffer.isView(v.elements) ? v.elements : v;
              if (!(Array.isArray(arr) || ArrayBuffer.isView(arr)) || arr.length !== 16) return false;
              for (let i = 0; i < 16; i++) {
                if (typeof arr[i] !== 'number' || !Number.isFinite(arr[i])) return false;
              }
              return true;
            };

            const extractEntityRecord = function(item, itemPath) {
              if (!item || typeof item !== 'object' || __isSpecialHostObject(item)) return null;
              if (item === playerObj) return null;
              const vec = __extractVec3(item);
              if (!vec) return null;

              const dx = vec.x - playerVec.x;
              const dy = vec.y - playerVec.y;
              const dz = vec.z - playerVec.z;
              const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

              let sampleKeys = [];
              try { sampleKeys = Object.keys(item).slice(0, 10); } catch (_) {}
              const name = String(item.name || item.title || item.label || item.type || (item.constructor && item.constructor.name) || 'Entity').slice(0, 36);
              const id = String(item.id !== undefined ? item.id : (item.uid !== undefined ? item.uid : (item.uuid !== undefined ? item.uuid : ''))).slice(0, 36);

              return {
                name: name,
                id: id,
                path: itemPath,
                world: {
                  x: Number(vec.x.toFixed(3)),
                  y: Number(vec.y.toFixed(3)),
                  z: Number(vec.z.toFixed(3))
                },
                distanceToPlayer: Number(dist.toFixed(2)),
                sampleKeys: sampleKeys
              };
            };

            const evaluateCollection = function(coll, collPath) {
              if (!coll || typeof coll !== 'object' || __isSpecialHostObject(coll)) return null;
              const found = [];

              if (Array.isArray(coll)) {
                const len = Math.min(coll.length, 100);
                for (let i = 0; i < len && found.length < 40; i++) {
                  let el;
                  try { el = coll[i]; } catch (_) { continue; }
                  const rec = extractEntityRecord(el, collPath + '[' + i + ']');
                  if (rec) found.push(rec);
                }
              } else if (typeof Map !== 'undefined' && coll instanceof Map) {
                let count = 0;
                coll.forEach(function(val, mk) {
                  if (count++ >= 100 || found.length >= 40) return;
                  const rec = extractEntityRecord(val, collPath + '.get(' + JSON.stringify(String(mk)) + ')');
                  if (rec) found.push(rec);
                });
              } else if (typeof Set !== 'undefined' && coll instanceof Set) {
                let idx = 0;
                coll.forEach(function(val) {
                  if (idx >= 100 || found.length >= 40) { idx++; return; }
                  const rec = extractEntityRecord(val, collPath + '[set#' + idx + ']');
                  if (rec) found.push(rec);
                  idx++;
                });
              } else {
                let keys;
                try { keys = Object.keys(coll); } catch (_) { return null; }
                if (keys.length < 2 || keys.length > 400) return null;
                const lim = Math.min(keys.length, 80);
                for (let i = 0; i < lim && found.length < 40; i++) {
                  const ek = keys[i];
                  let el;
                  try { el = coll[ek]; } catch (_) { continue; }
                  const rec = extractEntityRecord(el, collPath + '.' + ek);
                  if (rec) found.push(rec);
                }
              }

              if (found.length === 0) return null;
              found.sort(function(a, b) { return a.distanceToPlayer - b.distanceToPlayer; });
              const nearbyCount = found.filter(function(e) { return e.distanceToPlayer <= 2000; }).length;
              const nameBonus = /(entit|monster|mob|enemy|enemies|npc|actor|player|unit|object|children|sprite|char)/i.test(collPath) ? 40 : 0;
              return {
                collectionPath: collPath,
                entities: found,
                score: (found.length * 10) + (nearbyCount * 15) + nameBonus
              };
            };

            const inspectionTasks = [];
            const seenObjs = new WeakSet();
            for (let a = 0; a < ancestors.length; a++) {
              const anc = ancestors[a];
              if (seenObjs.has(anc.obj)) continue;
              seenObjs.add(anc.obj);
              let keys = [];
              try { keys = Object.keys(anc.obj); } catch (_) {}
              const lim = Math.min(keys.length, 72);
              for (let i = 0; i < lim; i++) {
                const k = keys[i];
                if (k.startsWith('__wvDiag')) continue;
                inspectionTasks.push({ parent: anc.obj, key: k, path: anc.path + '.' + k, depth: 1 });
              }
            }

            let vpMatrix = null;
            let matrixSource = 'pinhole_viewport_fallback';
            const candidateCollections = [];
            let cursor = 0;
            let chunksExecuted = 0;

            const processChunk = function() {
              if (mySeq !== __state.scanSeq) return;
              chunksExecuted++;
              const sliceStart = performance.now();

              while (cursor < inspectionTasks.length && (performance.now() - sliceStart) < __CHUNK_BUDGET_MS) {
                const task = inspectionTasks[cursor++];
                let val;
                try { val = task.parent[task.key]; } catch (_) { continue; }
                if (!val || typeof val !== 'object' || __isSpecialHostObject(val)) continue;

                if (!vpMatrix && /(viewProjection|projectionMatrix|mvp|vpMatrix)/i.test(task.key) && isMat4(val)) {
                  const raw = Array.isArray(val.elements) || ArrayBuffer.isView(val.elements) ? val.elements : val;
                  vpMatrix = Array.from(raw);
                  matrixSource = task.path;
                }

                const evalRes = evaluateCollection(val, task.path);
                if (evalRes) candidateCollections.push(evalRes);

                if (task.depth === 1 && !Array.isArray(val) && !seenObjs.has(val) &&
                    /(scene|world|manager|entity|entities|mob|monster|npc|actor|room|level|stage|camera|cam|render|net|state|players)/i.test(task.key)) {
                  seenObjs.add(val);
                  let subKeys = [];
                  try { subKeys = Object.keys(val); } catch (_) {}
                  const subLim = Math.min(subKeys.length, 32);
                  for (let j = 0; j < subLim; j++) {
                    inspectionTasks.push({ parent: val, key: subKeys[j], path: task.path + '.' + subKeys[j], depth: 2 });
                  }
                }
              }

              if (cursor < inspectionTasks.length) {
                __setStatus('RUNNING', 'Step 3: Scanning sibling containers…');
                __yieldFrame(processChunk);
              } else {
                candidateCollections.sort(function(a, b) { return b.score - a.score; });
                const bestColl = candidateCollections.length > 0 ? candidateCollections[0] : null;
                const rawEntities = bestColl ? bestColl.entities.slice(0, 30) : [];

                const projectToScreen = function(w) {
                  if (vpMatrix) {
                    const m = vpMatrix;
                    const cx = m[0]*w.x + m[4]*w.y + m[8]*w.z + m[12];
                    const cy = m[1]*w.x + m[5]*w.y + m[9]*w.z + m[13];
                    const cw = m[3]*w.x + m[7]*w.y + m[11]*w.z + m[15];
                    const invW = Math.abs(cw) > 1e-6 ? (1 / cw) : 1;
                    const sx = ((cx * invW) * 0.5 + 0.5) * vpWidth;
                    const sy = (-(cy * invW) * 0.5 + 0.5) * vpHeight;
                    return {
                      screenX: Number(sx.toFixed(1)),
                      screenY: Number(sy.toFixed(1)),
                      inViewport: sx >= 0 && sx <= vpWidth && sy >= 0 && sy <= vpHeight && cw > 0
                    };
                  }
                  const dx = w.x - playerVec.x;
                  const dy = w.y - playerVec.y;
                  const dz = (w.z - playerVec.z) + 10.0;
                  const focal = Math.min(vpWidth, vpHeight) * 0.85;
                  const depthSafe = Math.abs(dz) < 0.1 ? 0.1 : Math.abs(dz);
                  const sx = (vpWidth * 0.5) + (dx / depthSafe) * focal;
                  const sy = (vpHeight * 0.5) - (dy / depthSafe) * focal;
                  return {
                    screenX: Number(sx.toFixed(1)),
                    screenY: Number(sy.toFixed(1)),
                    inViewport: sx >= 0 && sx <= vpWidth && sy >= 0 && sy <= vpHeight
                  };
                };

                const projected = rawEntities.map(function(ent) {
                  const proj = projectToScreen(ent.world);
                  return {
                    name: ent.name,
                    id: ent.id,
                    path: ent.path,
                    world: ent.world,
                    distanceToPlayer: ent.distanceToPlayer,
                    screenX: proj.screenX,
                    screenY: proj.screenY,
                    inViewport: proj.inViewport,
                    sampleKeys: ent.sampleKeys
                  };
                });

                const entityArrayPath = bestColl ? bestColl.collectionPath : null;
                __state.discoveredEntityArrayPath = entityArrayPath;
                __state.entityProjectionMode = matrixSource;
                __state.discoveredEntities = projected;

                __setStatus(
                  projected.length > 0 ? 'COMPLETED' : 'IDLE',
                  projected.length > 0
                    ? ('Step 3: ' + projected.length + ' entities found')
                    : 'Step 3: 0 sibling entities found'
                );

                __emit('ENTITY', {
                  stage: 'ENTITIES_SCANNED',
                  chunksExecuted: chunksExecuted,
                  anchoredPlayerPath: anchorPath,
                  ancestorsInspected: ancestors.map(function(a) { return a.path; }),
                  entityArrayPath: entityArrayPath,
                  alternateCollections: candidateCollections.slice(1, 5).map(function(c) {
                    return { path: c.collectionPath, count: c.entities.length };
                  }),
                  viewport: { width: Math.round(vpWidth), height: Math.round(vpHeight) },
                  projectionMode: matrixSource,
                  entityCount: projected.length,
                  entities: projected
                });
              }
            };

            __yieldFrame(processChunk);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Step 3 error');
                window.TelemetryBridge.logTelemetry('ERR', 'Step 3 Entity scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 5 Export Collector: Collects live viewport metrics, live player reference sample,
     * and confirmed paths via circular-safe serialization.
     */
    fun buildCollectViewportMetricsScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const canvases = Array.from(document.querySelectorAll('canvas')).slice(0, 8).map(function(c, idx) {
              const r = c.getBoundingClientRect();
              return {
                index: idx,
                id: c.id || null,
                width: c.width,
                height: c.height,
                cssWidth: Math.round(r.width),
                cssHeight: Math.round(r.height)
              };
            });

            const livePlayer = __readLiveRefVec();
            const refKeys = __state.liveRef ? {
              x: __state.liveRef.xKey,
              y: __state.liveRef.yKey,
              z: __state.liveRef.zKey
            } : null;

            const payload = {
              url: location.href,
              title: document.title || '',
              readyState: document.readyState,
              userAgent: navigator.userAgent,
              viewport: {
                width: window.innerWidth,
                height: window.innerHeight,
                devicePixelRatio: window.devicePixelRatio || 1,
                scrollX: window.scrollX,
                scrollY: window.scrollY
              },
              canvases: canvases,
              discoveredPlayerPath: (__state.liveRef && __state.liveRef.path) || __state.discoveredPlayerPath || null,
              liveRefPropertyKeys: refKeys,
              livePlayerSample: livePlayer,
              discoveredEntityArrayPath: __state.discoveredEntityArrayPath || null,
              entityProjectionMode: __state.entityProjectionMode || ''
            };
            if (__bridge && typeof __bridge.exportReport === 'function') {
              __bridge.exportReport(__safeSerialize(payload, 0));
            }
          } catch (err) {
            try {
              if (window.TelemetryBridge && typeof window.TelemetryBridge.exportReport === 'function') {
                window.TelemetryBridge.exportReport(JSON.stringify({ collectionError: String(err) }));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Manual JS safe evaluator with circular/DOM/WebGL protection.
     */
    fun buildManualEvalScript(rawCode: String): String {
        val escapedCodeLiteral = JSONObject.quote(rawCode)
        return """
            (function() {
              try {
                $SHARED_RUNTIME
                const code = $escapedCodeLiteral;
                __setStatus('RUNNING', 'Evaluating JS…');
                __yieldFrame(function() {
                  try {
                    const t0 = performance.now();
                    let result;
                    let mode = 'expr';
                    try {
                      result = (0, eval)('(' + code + ')');
                    } catch (syntaxErr) {
                      mode = 'stmt';
                      result = (0, eval)(code);
                    }
                    const durationMs = Number((performance.now() - t0).toFixed(2));

                    let formatted;
                    if (result === undefined) formatted = 'undefined';
                    else if (result === null) formatted = 'null';
                    else if (typeof result === 'function') formatted = '[Function ' + (result.name || 'anonymous') + ']';
                    else if (__isSpecialHostObject(result)) formatted = __describeSkippedObject(result);
                    else if (typeof result === 'object') {
                      formatted = __safeSerialize(result, 2);
                      if (formatted.length > 4000) formatted = formatted.slice(0, 4000) + '\n… [truncated]';
                    } else {
                      formatted = String(result);
                    }

                    __setStatus('COMPLETED', 'JS eval (' + durationMs + 'ms)');
                    __emit('JS', {
                      stage: 'MANUAL_EVAL',
                      mode: mode,
                      durationMs: durationMs,
                      expression: code.slice(0, 140),
                      resultType: typeof result,
                      result: formatted
                    });
                  } catch (evalErr) {
                    __setStatus('ERROR', 'JS eval failed');
                    __emit('ERR', 'JS Eval error: ' + (evalErr && evalErr.stack ? evalErr.stack.slice(0, 600) : String(evalErr)));
                  }
                });
              } catch (err) {
                try {
                  if (window.TelemetryBridge) {
                    window.TelemetryBridge.updateScanStatus('ERROR', 'JS eval failed');
                    window.TelemetryBridge.logTelemetry('ERR', 'JS Eval error: ' + String(err));
                  }
                } catch (_) {}
              }
            })();
        """.trimIndent()
    }
}
