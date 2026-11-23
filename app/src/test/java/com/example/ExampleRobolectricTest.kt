package com.example

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("WebViewDiagnosticsApp", appName)
    }

    @Test
    fun `url normalization prepends https and blocks disallowed schemes`() {
        val bareDomain = DiagnosticsViewModel.normalizeAndValidateUrl("example.com/demo")
        assertTrue(bareDomain is UrlValidationResult.Valid)
        assertEquals("https://example.com/demo", (bareDomain as UrlValidationResult.Valid).normalizedUrl)

        val blank = DiagnosticsViewModel.normalizeAndValidateUrl("")
        assertEquals("about:blank", (blank as UrlValidationResult.Valid).normalizedUrl)

        val jsScheme = DiagnosticsViewModel.normalizeAndValidateUrl("javascript:alert(1)")
        assertTrue(jsScheme is UrlValidationResult.Invalid)

        val fileScheme = DiagnosticsViewModel.normalizeAndValidateUrl("file:///etc/hosts")
        assertTrue(fileScheme is UrlValidationResult.Invalid)

        assertTrue(DiagnosticsViewModel.isAllowedNavigationUri(Uri.parse("https://threejs.org/examples/")))
        assertFalse(DiagnosticsViewModel.isAllowedNavigationUri(Uri.parse("content://com.example/secret")))
    }

    @Test
    fun `asset request matcher identifies wasm json bin and chunk files`() {
        assertEquals(".wasm", DiagnosticsViewModel.matchTrackedAssetExtension("/build/engine.wasm"))
        assertEquals(".json", DiagnosticsViewModel.matchTrackedAssetExtension("/assets/scene.json?v=2"))
        assertEquals(".bin", DiagnosticsViewModel.matchTrackedAssetExtension("/models/mesh.bin"))
        assertEquals("chunk", DiagnosticsViewModel.matchTrackedAssetExtension("/static/js/412.chunk.js"))
        assertNull(DiagnosticsViewModel.matchTrackedAssetExtension("/styles/main.css"))
    }

    @Test
    fun `staging buffer accumulates across multiple scans without overwriting and preserves pins`() {
        val scan1 = DiagnosticsViewModel.mergeIntoStagingBuffer(
            existing = emptyList(),
            incoming = listOf("window.game" to "GameEngine (12 keys)", "window.scene" to "Scene (5 keys)"),
            sourceStep = "Step 1",
            scanNumber = 1,
            timestamp = "12:00:01.000"
        )
        assertEquals(2, scan1.size)

        // Pin window.game
        val withPin = scan1.map { if (it.path == "window.game") it.copy(isPinned = true) else it }

        // Second scan discovers new player path and re-observes window.game
        val scan2 = DiagnosticsViewModel.mergeIntoStagingBuffer(
            existing = withPin,
            incoming = listOf("window.game" to "GameEngine (14 keys)", "window.game.player.pos" to "Δ +4.20"),
            sourceStep = "Step 2B",
            scanNumber = 2,
            timestamp = "12:00:08.500"
        )

        assertEquals(3, scan2.size)
        val pinnedGame = scan2.first()
        assertEquals("window.game", pinnedGame.path)
        assertTrue(pinnedGame.isPinned)
        assertEquals(2, pinnedGame.seenCount)
        assertTrue(scan2.any { it.path == "window.scene" })
        assertTrue(scan2.any { it.path == "window.game.player.pos" })
    }

    @Test
    fun `telemetry bridge guards exportReport and forwards scan status and live 200ms player coords`() {
        val logged = mutableListOf<Pair<String, String>>()
        val exported = mutableListOf<String>()
        val statuses = mutableListOf<Pair<AsyncScanStatus, String>>()
        val coords = mutableListOf<PlayerCoordinates>()

        val bridge = TelemetryBridge(
            onTelemetryReceived = { cat, payload -> logged.add(cat to payload) },
            onReportPayloadReceived = { json -> exported.add(json) },
            onScanStatusChanged = { status, detail -> statuses.add(status to detail) },
            onPlayerCoordsUpdated = { path, x, y, z -> coords.add(PlayerCoordinates(path, x, y, z)) }
        )

        bridge.exportReport("""{"injected":true}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exported.isEmpty())
        assertEquals("ERR", logged.first().first)

        bridge.armExport()
        bridge.exportReport("""{"url":"https://example.com"}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exported.size)

        bridge.updateScanStatus("COMPLETED", "Step 1: 12 globals\nin 1.1ms")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AsyncScanStatus.COMPLETED, statuses.first().first)
        assertEquals("Step 1: 12 globals in 1.1ms", statuses.first().second)

        bridge.updatePlayerCoords("window.game.player.pos", Double.NaN, 0.0, 0.0)
        bridge.updatePlayerCoords("window.game.player.pos", 12.5, 3.0, -44.25)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, coords.size)
        assertEquals("window.game.player.pos", coords.first().path)
        assertEquals(12.5, coords.first().x, 0.001)
    }

    @Test
    fun `diagnostic scripts support 100-item batched scanning with pause resume and direct liveRef`() {
        val step1 = DiagnosticsScripts.buildInspectGlobalScopeScript()
        val pauseScript = DiagnosticsScripts.buildPauseScopeScanScript()
        val resumeScript = DiagnosticsScripts.buildResumeScopeScanScript()
        val step2b = DiagnosticsScripts.buildComputeDeltaScript()
        val step3 = DiagnosticsScripts.buildScanEntitiesScript("window.game.player.position")

        // Step 1 strict built-in blacklist, 100-item batching, staggered async delays, and pause/resume
        assertTrue(step1.contains("const BATCH_SIZE = 100;"))
        assertTrue(step1.contains("Scanning batch "))
        assertTrue(step1.contains("GLOBAL_SCOPE_BATCH"))
        assertTrue(step1.contains("WebSocket"))
        assertTrue(step1.contains("Iterator"))
        assertTrue(step1.contains("DataTransfer"))
        assertTrue(step1.contains("[native code]"))
        assertTrue(pauseScript.contains("job.paused = true"))
        assertTrue(resumeScript.contains("job.paused = false"))

        // Step 2B direct nested reference (`parentObject[propName]` + `refObject`) and 200ms polling
        assertTrue(step2b.contains("parentObject:"))
        assertTrue(step2b.contains("propName:"))
        assertTrue(step2b.contains("refObject:"))
        assertTrue(step2b.contains("ref.parentObject[ref.propName]"))
        assertTrue(step2b.contains("__LIVE_POLL_INTERVAL_MS = 200"))
        assertTrue(step2b.contains("setInterval(pushNow, __LIVE_POLL_INTERVAL_MS)"))

        // Step 3 parent-climbing visual entity discovery
        assertTrue(step3.contains("window.game.player.position"))
        assertTrue(step3.contains("for (let climb = 1; climb <= 3; climb++)"))
    }

    @Test
    fun `staging keyword search filters by multi-token query and telemetry bridge reports batch progress`() {
        val items = listOf(
            CandidateStagingItem("window.game.player.pos", "Step 2B", "Vec3(12, 0, -5)", 2, 2, true, "12:00:01.000"),
            CandidateStagingItem("window.game.actors", "Step 1 B1", "Array (8 keys)", 1, 1, false, "12:00:00.500"),
            CandidateStagingItem("window.uiTheme", "Step 1 B2", "Object (3 keys)", 1, 1, false, "12:00:00.800")
        )

        val posMatches = DiagnosticsViewModel.filterStagingBuffer(items, showPinnedOnly = false, rawQuery = "pos")
        assertEquals(1, posMatches.size)
        assertEquals("window.game.player.pos", posMatches.first().path)

        val actorMatches = DiagnosticsViewModel.filterStagingBuffer(items, showPinnedOnly = false, rawQuery = "actor")
        assertEquals(1, actorMatches.size)
        assertEquals("window.game.actors", actorMatches.first().path)

        val pinnedActorMatches = DiagnosticsViewModel.filterStagingBuffer(items, showPinnedOnly = true, rawQuery = "actor")
        assertTrue(pinnedActorMatches.isEmpty())

        val progressEvents = mutableListOf<ScopeBatchProgress>()
        val bridge = TelemetryBridge(
            onTelemetryReceived = { _, _ -> },
            onReportPayloadReceived = {},
            onBatchProgressChanged = { progressEvents.add(it) }
        )
        bridge.updateBatchProgress(
            currentBatch = 2,
            totalBatches = 10,
            itemStart = 101,
            itemEnd = 200,
            totalItems = 950,
            foundCount = 4,
            isActive = true,
            isPaused = false
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, progressEvents.size)
        assertEquals("Scanning batch 2/10 (items 101–200)…", progressEvents.first().statusText)
    }

    @Test
    fun `full log dump text includes staged variables entities assets and rolling log history`() {
        val state = DiagnosticsUiState(
            activeUrl = "https://example.com/game",
            pageTitle = "Demo World",
            scanCounter = 2,
            discoveredPlayerPath = "window.game.player.pos",
            playerPropertyKeys = "x, y, z",
            livePlayerCoords = PlayerCoordinates("window.game.player.pos", 10.5, 2.0, -4.25),
            stagingBuffer = listOf(
                CandidateStagingItem("window.game.player.pos", "Step 2B", "Live ref", 2, 2, true, "12:01:00.000")
            ),
            discoveredEntityArrayPath = "window.game.entities",
            discoveredEntities = listOf(
                DiscoveredEntitySample("Goblin", "e1", "window.game.entities[0]", 15.0, 2.0, -1.0, 5.5, 320.0, 180.0, true, listOf("x", "y", "z"))
            ),
            interceptedAssets = listOf(
                InterceptedAsset("https://example.com/engine.wasm", ".wasm", "GET", "12:00:10.000", false)
            ),
            logs = listOf(
                TelemetryLogEntry(1L, "12:00:11.000", 1700000000000L, LogCategory.SCOPE, "Batch 1/10 complete", "{\"ok\":true}")
            )
        )

        val dump = DiagnosticsViewModel.buildFullLogDumpText(state)
        assertTrue(dump.contains("WebViewDiagnosticsApp — Complete Diagnostic Log Dump"))
        assertTrue(dump.contains("[PINNED] window.game.player.pos"))
        assertTrue(dump.contains("Goblin #e1 (IN VIEW)"))
        assertTrue(dump.contains("https://example.com/engine.wasm"))
        assertTrue(dump.contains("Batch 1/10 complete"))
    }

    @Test
    fun `binary asset inspector decodes wasm json and float32 bin headers via ByteBuffer`() {
        // 1. Minimal WASM binary: \0asm + version 1 + Type section (id=1, size=1, body=0x00)
        val wasmBytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x01, 0x00
        )
        val wasmAudit = BinaryAssetInspector.inspectBytes(wasmBytes, ".wasm")
        assertEquals("WASM v1", wasmAudit.magicSignature)
        assertTrue(wasmAudit.structuralDetails.contains("Type(1 B)"))
        assertTrue(wasmAudit.hexHeaderPreview.startsWith("00 61 73 6D 01 00 00 00"))

        // 2. JSON asset inspection
        val jsonBytes = """{"scene":"dungeon","entities":[1,2,3],"version":4}""".toByteArray(Charsets.UTF_8)
        val jsonAudit = BinaryAssetInspector.inspectBytes(jsonBytes, ".json", "application/json")
        assertEquals("JSON Object (3 keys)", jsonAudit.magicSignature)
        assertTrue(jsonAudit.structuralSummary.contains("scene"))

        // 3. Little-endian Float32 .bin buffer inspection
        val bb = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        listOf(12.5f, 3.0f, -44.25f, 1.0f, 0.0f, 2.5f).forEach { bb.putFloat(it) }
        val binAudit = BinaryAssetInspector.inspectBytes(bb.array(), ".bin")
        assertEquals("Binary Float32 Buffer", binAudit.magicSignature)
        assertTrue(binAudit.structuralDetails.contains("12.50, 3.00, -44.25"))

        // 4. Filename sanitization prevents path traversal
        val safeName = BinaryAssetInspector.extractFileName("https://cdn.example.com/../../evil?x=1", ".bin")
        assertFalse(safeName.contains("/"))
        assertFalse(safeName.contains("\\"))
    }

    @Test
    fun `step 1 async yielding batches targeted game roots and manual path inspector stage immediately`() {
        val step1 = DiagnosticsScripts.buildInspectGlobalScopeScript()
        val queryScript = DiagnosticsScripts.buildQueryPropertyPathScript("game.player")

        // Verify true async yielding loop (15ms Promise yield + rAF) and targeted engine roots
        assertTrue(step1.contains("(async function()"))
        assertTrue(step1.contains("const BATCH_YIELD_MS = 15;"))
        assertTrue(step1.contains("await __yieldBatchAsync(BATCH_YIELD_MS)"))
        assertTrue(step1.contains("GAME_NAMESPACE_DETECTED"))
        assertTrue(step1.contains("'Phaser'"))
        assertTrue(step1.contains("'PIXI'"))
        assertTrue(step1.contains("'BABYLON'"))
        assertTrue(step1.contains("'Module'"))
        assertTrue(step1.contains("ArrayBuffer.isView(val)"))

        // Verify manual property inspector script
        assertTrue(queryScript.contains("MANUAL_PATH_QUERY"))
        assertTrue(queryScript.contains("game.player"))

        // Verify ViewModel stages auto-detected game roots and pins manual path queries
        val vm = DiagnosticsViewModel()
        vm.onTelemetryFromBridge(
            "SCOPE",
            """{"stage":"GAME_NAMESPACE_DETECTED","detectedNamespaces":["window.game","window.Module"],"detectedEntries":[{"key":"game","path":"window.game","constructor":"GameEngine","keyCount":14,"previewKeys":["player","scene"]},{"key":"Module","path":"window.Module","constructor":"Object","keyCount":42,"previewKeys":["HEAPU8","asm"]}]}"""
        )
        assertTrue(vm.uiState.value.detectedGameNamespaces.contains("window.game"))
        assertTrue(vm.uiState.value.detectedGameNamespaces.contains("window.Module"))
        assertTrue(vm.uiState.value.stagingBuffer.any { it.path == "window.game" && it.sourceStep == "Auto-Root" })

        vm.onTelemetryFromBridge(
            "SCOPE",
            """{"stage":"MANUAL_PATH_QUERY","found":true,"requestedPath":"game.player","normalizedPath":"window.game.player","rootKey":"game","type":"object","constructor":"PlayerEntity","keyCount":9,"detail":"PlayerEntity Vec3(12.00, 1.50, -8.25)","childEntries":[{"path":"window.game.player.position","detail":"Vector3 (3 keys)"}]}"""
        )
        val stagedPlayer = vm.uiState.value.stagingBuffer.firstOrNull { it.path == "window.game.player" }
        assertTrue(stagedPlayer != null)
        assertTrue(stagedPlayer!!.isPinned)
        assertEquals("Manual Query", stagedPlayer.sourceStep)
        assertTrue(vm.uiState.value.stagingBuffer.any { it.path == "window.game.player.position" })
        assertTrue(vm.uiState.value.lastManualQuerySummary.contains("window.game.player"))
    }

    @Test
    fun `step 2a and 2b explicitly exclude wasm heaps wasmTableMirror and typed arrays with sub-5ms cap`() {
        val step2a = DiagnosticsScripts.buildCaptureBaselineScript()
        val step2b = DiagnosticsScripts.buildComputeDeltaScript()

        // Explicit WebAssembly memory keys & wasmTableMirror exclusions
        listOf(
            "HEAP8", "HEAPU8", "HEAP16", "HEAPU16", "HEAP32", "HEAPU32",
            "HEAPF32", "HEAPF64", "HEAP64", "HEAPU64", "wasmTableMirror"
        ).forEach { wasmKey ->
            assertTrue("Step 2A must exclude $wasmKey", step2a.contains("'$wasmKey'"))
            assertTrue("Step 2B must exclude $wasmKey", step2b.contains("'$wasmKey'"))
        }

        // Strict binary / TypedArray / ArrayBuffer / plain-object filtering and <5ms cap
        assertTrue(step2a.contains("const MAX_TOTAL_MS = 4.5;"))
        assertTrue(step2a.contains("const MAX_DEPTH = 2;"))
        assertTrue(step2a.contains("__isSafeBaselineObject"))
        assertTrue(step2a.contains("__isExcludedBinaryOrWasmObject"))
        assertTrue(step2a.contains("ArrayBuffer.isView(val)"))
        assertTrue(step2a.contains("val instanceof ArrayBuffer"))
        assertTrue(step2a.contains("WebAssembly.Memory"))
        assertTrue(step2b.contains("__isExcludedWasmKey(p)"))
        assertTrue(step2b.contains("__isSafeBaselineObject(parentObj)"))
    }

    @Test
    fun `telemetry bridge forwards wasm neighboring memory snapshots on main thread`() {
        val snapshots = mutableListOf<String>()
        val bridge = TelemetryBridge(
            onTelemetryReceived = { _, _ -> },
            onReportPayloadReceived = {},
            onWasmNeighborsUpdated = { snapshots.add(it) }
        )

        bridge.updateWasmNeighbors("""{"stage":"WASM_NEIGHBORS_TICK","isolatedIndex":1024,"neighbors":[]}""")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, snapshots.size)
        assertTrue(snapshots.first().contains("WASM_NEIGHBORS_TICK"))
        assertTrue(snapshots.first().contains("1024"))
    }

    @Test
    fun `wasm heap viewmodel tracks scan delta neighbors and exports memory map json`() {
        val vm = DiagnosticsViewModel()
        vm.onWasmTargetValueChanged("4338")
        assertEquals("4338", vm.uiState.value.wasmHeap.targetValueInput)

        // 1. Simulate initial scan finding 42 matches
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_HEAP_SCAN_COMPLETE","durationMs":14.2,"heapSourceLabel":"window.HEAP32 / window.HEAPF32","heapByteLength":67108864,"targetValue":4338,"totalMatches":42,"heap32MatchCount":40,"heapF32MatchCount":2,"samples":[{"heapType":"HEAP32","index":8192,"byteAddress":32768,"currentValue":"4338"}]}"""
        )
        val afterScan = vm.uiState.value.wasmHeap
        assertEquals(42, afterScan.totalMatches)
        assertEquals(40, afterScan.heap32MatchCount)
        assertEquals(2, afterScan.heapF32MatchCount)
        assertFalse(afterScan.isNeighborPolling)

        // 2. Simulate Value-Delta filter narrowing to 1 isolated address
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_HEAP_DELTA_FILTERED","durationMs":0.4,"heapSourceLabel":"window.HEAP32 / window.HEAPF32","heapByteLength":67108864,"filterRound":1,"newValue":4120,"previousCount":42,"totalMatches":1,"heap32MatchCount":1,"heapF32MatchCount":0,"samples":[{"heapType":"HEAP32","index":8192,"byteAddress":32768,"currentValue":"4120"}],"autoIsolated":{"heapType":"HEAP32","index":8192,"byteAddress":32768,"currentValue":"4120"}}"""
        )
        val afterFilter = vm.uiState.value.wasmHeap
        assertEquals(1, afterFilter.totalMatches)
        assertEquals(8192, afterFilter.isolatedIndex)
        assertEquals("HEAP32", afterFilter.isolatedHeapType)
        assertEquals(32768L, afterFilter.isolatedByteAddress)
        assertTrue(afterFilter.isNeighborPolling)

        // 3. Simulate two neighbor polling ticks where +4B (X coord) changes as player walks
        vm.onWasmNeighborsUpdatedFromBridge(
            """{"stage":"WASM_NEIGHBORS_TICK","heapSourceLabel":"window.HEAP32 / window.HEAPF32","heapByteLength":67108864,"heapType":"HEAP32","isolatedIndex":8192,"isolatedByteAddress":32768,"currentValue":"4120","tick":1,"neighbors":[{"byteOffset":-4,"heapIndex":8191,"byteAddress":32764,"floatValue":100.0,"intValue":1120403456},{"byteOffset":0,"heapIndex":8192,"byteAddress":32768,"floatValue":0.0,"intValue":4120},{"byteOffset":4,"heapIndex":8193,"byteAddress":32772,"floatValue":250.5,"intValue":1132085248}]}"""
        )
        vm.onWasmNeighborsUpdatedFromBridge(
            """{"stage":"WASM_NEIGHBORS_TICK","heapSourceLabel":"window.HEAP32 / window.HEAPF32","heapByteLength":67108864,"heapType":"HEAP32","isolatedIndex":8192,"isolatedByteAddress":32768,"currentValue":"4120","tick":2,"neighbors":[{"byteOffset":-4,"heapIndex":8191,"byteAddress":32764,"floatValue":100.0,"intValue":1120403456},{"byteOffset":0,"heapIndex":8192,"byteAddress":32768,"floatValue":0.0,"intValue":4120},{"byteOffset":4,"heapIndex":8193,"byteAddress":32772,"floatValue":256.25,"intValue":1132470272}]}"""
        )

        val neighbors = vm.uiState.value.wasmHeap.neighborFloats
        assertEquals(3, neighbors.size)
        val movingCoord = neighbors.first { it.byteOffset == 4 }
        assertTrue(movingCoord.changedFromPrev)
        assertEquals(1, movingCoord.changeCount)
        assertEquals(256.25, movingCoord.floatValue, 0.001)

        val staticNeighbor = neighbors.first { it.byteOffset == -4 }
        assertFalse(staticNeighbor.changedFromPrev)
        assertEquals(0, staticNeighbor.changeCount)

        // 4. Verify exported Memory Map JSON contains isolated address & dynamic coordinate candidate
        val mapJson = DiagnosticsViewModel.buildWasmMemoryMapJson(vm.uiState.value)
        assertTrue(mapJson.contains("wasm_heap_memory_map"))
        assertTrue(mapJson.contains("0x00008000"))
        assertTrue(mapJson.contains("\"isDynamicCoordinateCandidate\": true"))
        assertTrue(mapJson.contains("256.25"))
    }

    @Test
    fun `telemetry bridge and viewmodel track wasm float heap baseline movement deltas live watch and pinning`() {
        val ticks = mutableListOf<String>()
        val bridge = TelemetryBridge(
            onTelemetryReceived = { _, _ -> },
            onReportPayloadReceived = {},
            onWasmFloatCandidatesUpdated = { ticks.add(it) }
        )

        bridge.updateWasmFloatCandidates("""{"stage":"WASM_FLOAT_WATCH_TICK","tick":1,"candidates":[]}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ticks.size)

        val vm = DiagnosticsViewModel()
        assertEquals(Step2ScanMode.WASM_FLOAT_HEAP, vm.uiState.value.step2ScanMode)

        // 1. Baseline captured
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_FLOAT_BASELINE_CAPTURED","durationMs":18.5,"heapSourceLabel":"window.HEAPF32","heapByteLength":67108864,"sampledCount":24000,"prunedJitterCount":115}"""
        )
        assertTrue(vm.uiState.value.wasmFloatDelta.baselineCaptured)
        assertEquals(24000, vm.uiState.value.wasmFloatDelta.baselineSampleCount)
        assertEquals(WizardStepBadge.READY, vm.uiState.value.step2Badge)

        // 2. Movement delta scan isolates X/Y/Z cluster
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_FLOAT_DELTA_SCANNED","durationMs":3.2,"heapSourceLabel":"window.HEAPF32","heapByteLength":67108864,"scanRound":1,"totalMatches":9,"candidates":[{"heapIndex":5000,"byteAddress":20000,"baselineValue":100.0,"currentValue":105.5,"delta":5.5,"clusterSize":3,"axisHint":"X"},{"heapIndex":5001,"byteAddress":20004,"baselineValue":10.0,"currentValue":10.0,"delta":0.0,"clusterSize":3,"axisHint":"Y"},{"heapIndex":5002,"byteAddress":20008,"baselineValue":-20.0,"currentValue":-16.25,"delta":3.75,"clusterSize":3,"axisHint":"Z"}]}"""
        )
        assertTrue(vm.uiState.value.wasmFloatDelta.isLiveWatching)
        assertEquals(3, vm.uiState.value.wasmFloatDelta.candidates.size)
        assertEquals(WizardStepBadge.CAPTURED, vm.uiState.value.step2Badge)

        // 3. Live watch tick updates X coordinate and increments changeCount
        vm.onWasmFloatCandidatesUpdatedFromBridge(
            """{"stage":"WASM_FLOAT_WATCH_TICK","heapSourceLabel":"window.HEAPF32","heapByteLength":67108864,"tick":2,"candidates":[{"heapIndex":5000,"byteAddress":20000,"baselineValue":100.0,"currentValue":108.25,"delta":8.25,"clusterSize":3,"axisHint":"X","isPinned":false}]}"""
        )
        val xCand = vm.uiState.value.wasmFloatDelta.candidates.first { it.heapIndex == 5000 }
        assertTrue(xCand.changedFromPrev)
        assertEquals(1, xCand.changeCount)
        assertEquals(108.25, xCand.currentValue, 0.001)

        // 4. Pinning X candidate adds it to pinnedIndices and pins in Staging buffer
        vm.onTogglePinWasmFloatCandidate(5000)
        assertTrue(vm.uiState.value.wasmFloatDelta.pinnedIndices.contains(5000))
        assertTrue(vm.uiState.value.stagingBuffer.any { it.path == "window.HEAPF32[5000]" && it.isPinned })
    }

    @Test
    fun `viewmodel locks movement coords discovers and verifies persistent pointer chains and exports json`() {
        val vm = DiagnosticsViewModel()

        // Seed movement delta candidates
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_FLOAT_DELTA_SCANNED","durationMs":2.1,"heapSourceLabel":"window.HEAPF32","heapByteLength":67108864,"scanRound":1,"totalMatches":3,"candidates":[{"heapIndex":5000,"byteAddress":20000,"baselineValue":100.0,"currentValue":105.5,"delta":5.5,"clusterSize":3,"axisHint":"X"},{"heapIndex":5001,"byteAddress":20004,"baselineValue":10.0,"currentValue":10.0,"delta":0.0,"clusterSize":3,"axisHint":"Y"},{"heapIndex":5002,"byteAddress":20008,"baselineValue":-20.0,"currentValue":-16.25,"delta":3.75,"clusterSize":3,"axisHint":"Z"}]}"""
        )

        // 1. Lock coordinates from movement scan
        vm.onLockWasmFloatCoords()
        val locked = vm.uiState.value.wasmFloatDelta.lockedCoords
        assertTrue(locked != null)
        assertEquals(5000, locked!!.xIndex)
        assertEquals(5001, locked.yIndex)
        assertEquals(5002, locked.zIndex)
        assertEquals(20000L, locked.xByteAddress)
        assertEquals("window.HEAPF32[5000,5001,5002]", vm.uiState.value.discoveredPlayerPath)
        assertTrue(vm.uiState.value.stagingBuffer.any { it.path == "window.HEAPF32[5000,5001,5002]" && it.isPinned })

        // 2. Receive live watch tick updating locked X/Y/Z values
        vm.onWasmFloatCandidatesUpdatedFromBridge(
            """{"stage":"WASM_FLOAT_WATCH_TICK","tick":3,"candidates":[],"locked":{"xIndex":5000,"yIndex":5001,"zIndex":5002,"xValue":112.75,"yValue":10.0,"zValue":-14.0}}"""
        )
        assertEquals(112.75, vm.uiState.value.wasmFloatDelta.lockedCoords!!.xValue, 0.001)
        assertEquals(3, vm.uiState.value.wasmFloatDelta.lockedCoords!!.liveTickCount)

        // 3. Receive L1 & L2 pointer scan completion
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_POINTER_SCAN_COMPLETE","durationMs":11.4,"targetByteAddress":20000,"totalL1":4,"totalL2":2,"chains":[{"id":"L1_0x0000A100_+0x10","depth":1,"basePointerAddress":41216,"offsets":[16],"resolvedTargetAddress":20000,"resolvedX":112.75,"resolvedY":10.0,"resolvedZ":-14.0,"isLikelyStatic":true,"isVerifiedLive":true,"expression":"*(0x0000A100) + 0x10"},{"id":"L2_0x00002400_+0x8_+0x10","depth":2,"basePointerAddress":9216,"offsets":[8,16],"resolvedTargetAddress":20000,"resolvedX":112.75,"resolvedY":10.0,"resolvedZ":-14.0,"isLikelyStatic":true,"isVerifiedLive":true,"expression":"*(*(0x00002400) + 0x8) + 0x10"}]}"""
        )
        val fdAfterPtr = vm.uiState.value.wasmFloatDelta
        assertFalse(fdAfterPtr.isScanningPointers)
        assertEquals(4, fdAfterPtr.pointerScanTotalL1)
        assertEquals(2, fdAfterPtr.pointerScanTotalL2)
        assertEquals(2, fdAfterPtr.pointerChains.size)

        // 4. Pin pointer chain to Staging & verify live re-dereference
        vm.onTogglePinWasmPointerChain("L2_0x00002400_+0x8_+0x10")
        assertTrue(vm.uiState.value.wasmFloatDelta.pointerChains.first { it.id == "L2_0x00002400_+0x8_+0x10" }.isPinned)
        assertTrue(vm.uiState.value.stagingBuffer.any { it.path.contains("*(*(0x00002400) + 0x8) + 0x10") && it.isPinned })

        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_POINTER_CHAINS_VERIFIED","validCount":1,"totalCount":2,"results":[{"id":"L1_0x0000A100_+0x10","resolvedTargetAddress":0,"resolvedX":0,"resolvedY":0,"resolvedZ":0,"isVerifiedLive":false},{"id":"L2_0x00002400_+0x8_+0x10","resolvedTargetAddress":24000,"resolvedX":120.5,"resolvedY":10.0,"resolvedZ":-9.5,"isVerifiedLive":true}]}"""
        )
        val l1AfterVerify = vm.uiState.value.wasmFloatDelta.pointerChains.first { it.id == "L1_0x0000A100_+0x10" }
        val l2AfterVerify = vm.uiState.value.wasmFloatDelta.pointerChains.first { it.id == "L2_0x00002400_+0x8_+0x10" }
        assertFalse(l1AfterVerify.isVerifiedLive)
        assertTrue(l2AfterVerify.isVerifiedLive)
        assertEquals(24000L, l2AfterVerify.resolvedTargetAddress)
        assertEquals(120.5, l2AfterVerify.resolvedX, 0.001)

        // 5. Verify Memory Map JSON & Log Dump include locked coords and pointer chains
        val mapJson = DiagnosticsViewModel.buildWasmMemoryMapJson(vm.uiState.value)
        assertTrue(mapJson.contains("lockedCoords"))
        assertTrue(mapJson.contains("persistentPointerChains"))
        assertTrue(mapJson.contains("*(*(0x00002400) + 0x8) + 0x10"))

        val dumpText = DiagnosticsViewModel.buildFullLogDumpText(vm.uiState.value)
        assertTrue(dumpText.contains("Locked Coords"))
        assertTrue(dumpText.contains("Persistent Pointer Chains"))
        assertTrue(dumpText.contains("*(*(0x00002400) + 0x8) + 0x10"))
    }

    @Test
    fun `site pointer store persists bookmarks per host survives reset and auto-verifies on reload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SitePointerStore(InMemoryPointerStorage())
        val vm = DiagnosticsViewModel()
        vm.setSitePointerStoreForTesting(store)

        val url = "https://universe.flyff.com/play"
        vm.onUrlInputChanged(url)
        vm.onLoadUrlRequested()

        // Seed 3 discovered pointers matching the real Flyff export pattern: 2 live + 1 zero
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_POINTER_SCAN_COMPLETE","durationMs":12.0,"targetByteAddress":65011712,"totalL1":2,"totalL2":1,"chains":[{"id":"L1_0x00135C64_+0x98","depth":1,"basePointerAddress":1268836,"offsets":[152],"resolvedTargetAddress":65011712,"resolvedX":6961.98,"resolvedY":100.0,"resolvedZ":3309.76,"isLikelyStatic":true,"isVerifiedLive":true,"expression":"*(0x00135C64) + 0x98"},{"id":"L2_0x0005E678_+0xC_+0x98","depth":2,"basePointerAddress":386680,"offsets":[12,152],"resolvedTargetAddress":65011712,"resolvedX":6961.98,"resolvedY":100.0,"resolvedZ":3309.76,"isLikelyStatic":true,"isVerifiedLive":true,"expression":"*(*(0x0005E678) + 0xC) + 0x98"},{"id":"L1_0x001B1868_+0x98","depth":1,"basePointerAddress":1775720,"offsets":[152],"resolvedTargetAddress":70000000,"resolvedX":0.0,"resolvedY":0.0,"resolvedZ":0.0,"isLikelyStatic":true,"isVerifiedLive":false,"expression":"*(0x001B1868) + 0x98"}]}"""
        )

        // Auto-watch starts immediately after pointer discovery
        assertTrue(vm.uiState.value.wasmFloatDelta.isWatchingPointers)

        // Bookmark the 2 genuine chains -> saved locally per host
        vm.onTogglePinWasmPointerChain("L1_0x00135C64_+0x98")
        vm.onTogglePinWasmPointerChain("L2_0x0005E678_+0xC_+0x98")
        assertEquals(2, store.countSavedForUrl(url))
        assertEquals(2, vm.uiState.value.wasmFloatDelta.savedPointerCountForHost)

        // Live 200ms watch tick: L1 moves, L2 stays live, 3rd chain is ZERO (0,0,0)
        vm.onWasmPointersUpdatedFromBridge(
            """{"stage":"WASM_POINTER_WATCH_TICK","tick":5,"activeHudPointerId":"L1_0x00135C64_+0x98","results":[{"id":"L1_0x00135C64_+0x98","resolvedTargetAddress":65011712,"resolvedX":6970.5,"resolvedY":100.0,"resolvedZ":3315.25,"status":"MOVING","isVerifiedLive":true,"changedFromPrev":true,"changeCount":3,"lastDeltaMagnitude":8.52},{"id":"L2_0x0005E678_+0xC_+0x98","resolvedTargetAddress":65011712,"resolvedX":6961.98,"resolvedY":100.0,"resolvedZ":3309.76,"status":"LIVE","isVerifiedLive":true,"changedFromPrev":false,"changeCount":0,"lastDeltaMagnitude":0.0},{"id":"L1_0x001B1868_+0x98","resolvedTargetAddress":70000000,"resolvedX":0.0,"resolvedY":0.0,"resolvedZ":0.0,"status":"ZERO","isVerifiedLive":false,"changedFromPrev":false,"changeCount":0,"lastDeltaMagnitude":0.0}]}"""
        )
        val chainsAfterTick = vm.uiState.value.wasmFloatDelta.pointerChains
        assertEquals("L1_0x00135C64_+0x98", chainsAfterTick.first().id)
        assertEquals(WasmPointerStatus.MOVING, chainsAfterTick.first().status)
        assertEquals(3, chainsAfterTick.first().changeCount)
        assertEquals(WasmPointerStatus.ZERO, chainsAfterTick.first { it.id == "L1_0x001B1868_+0x98" }.status)

        // Stream moving pointer to HUD -> updates livePlayerCoords and lockedCoords
        vm.onStreamPointerChainToHud("L1_0x00135C64_+0x98")
        assertEquals("L1_0x00135C64_+0x98", vm.uiState.value.wasmFloatDelta.activeHudPointerId)
        assertEquals(6970.5, vm.uiState.value.livePlayerCoords!!.x, 0.001)
        assertTrue(vm.uiState.value.hasLiveWasmPlayerAnchor)

        // Prune 0/Stale removes unbookmarked ZERO chain while keeping saved live chains
        vm.onPruneZeroAndStalePointers()
        assertEquals(2, vm.uiState.value.wasmFloatDelta.pointerChains.size)
        assertFalse(vm.uiState.value.wasmFloatDelta.pointerChains.any { it.id == "L1_0x001B1868_+0x98" })

        // Reset float scanner preserves bookmarked site pointers
        vm.onResetWasmFloatDelta()
        assertEquals(2, vm.uiState.value.wasmFloatDelta.pointerChains.size)
        assertTrue(vm.uiState.value.wasmFloatDelta.pointerChains.all { it.isSavedLocally && it.isPinned })

        // Simulated page reload / fresh ViewModel instance restores saved pointers & triggers auto-verify
        val reloadedVm = DiagnosticsViewModel()
        reloadedVm.setSitePointerStoreForTesting(store)
        reloadedVm.onPageFinished(url, "Flyff Universe")
        assertEquals(2, reloadedVm.uiState.value.wasmFloatDelta.pointerChains.size)
        assertTrue(reloadedVm.uiState.value.wasmFloatDelta.isAutoVerifyingOnReload)

        // Also verify SharedPreferences-backed constructor works with Robolectric context
        val prefsStore = SitePointerStore(context)
        prefsStore.clearPointersForUrl(url)
        prefsStore.savePointerForUrl(url, chainsAfterTick.first())
        assertEquals(1, prefsStore.loadPointersForUrl(url).size)
    }

    @Test
    fun `telemetry bridge forwards wasm pointers and entities and step 3 scans nearby wasm entities`() {
        val ptrPayloads = mutableListOf<String>()
        val entityPayloads = mutableListOf<String>()
        val bridge = TelemetryBridge(
            onTelemetryReceived = { _, _ -> },
            onReportPayloadReceived = {},
            onWasmPointersUpdated = { ptrPayloads.add(it) },
            onWasmEntitiesUpdated = { entityPayloads.add(it) }
        )

        bridge.updateWasmPointers("""{"stage":"WASM_POINTER_WATCH_TICK","tick":1,"results":[]}""")
        bridge.updateWasmEntities("""{"stage":"WASM_ENTITIES_TICK","tick":1,"entities":[]}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ptrPayloads.size)
        assertEquals(1, entityPayloads.size)

        val vm = DiagnosticsViewModel()
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_POINTER_SCAN_COMPLETE","durationMs":8.0,"targetByteAddress":65011712,"totalL1":1,"totalL2":0,"chains":[{"id":"L1_0x00135C64_+0x98","depth":1,"basePointerAddress":1268836,"offsets":[152],"resolvedTargetAddress":65011712,"resolvedX":6961.98,"resolvedY":100.0,"resolvedZ":3309.76,"isLikelyStatic":true,"isVerifiedLive":true,"expression":"*(0x00135C64) + 0x98"}]}"""
        )
        assertTrue(vm.uiState.value.hasLiveWasmPlayerAnchor)

        // Trigger Step 3 -> runs WASM nearby-entity scan and receives discovered entities
        vm.runStep3ScanEntities()
        assertEquals(AsyncScanStatus.RUNNING, vm.uiState.value.scanStatus)

        vm.onTelemetryFromBridge(
            "ENTITY",
            """{"stage":"WASM_ENTITIES_DISCOVERED","arrayPath":"window.HEAPF32 near 0x03E00000","projectionMode":"WASM Spatial HEAPF32 (radius 1.5..250u)","viewportWidth":1080,"viewportHeight":1920,"durationMs":14.5,"entities":[{"name":"WASM Entity #1","id":"0x03E01000","path":"HEAPF32[16253990] @ 0x03E01098","worldX":6975.0,"worldY":100.0,"worldZ":3312.5,"distanceToPlayer":13.3,"screenX":0,"screenY":0,"inViewport":true,"sampleKeys":["WASM_STRUCT","struct=0x03E01000","Δ(13.0, 0.0, 2.7)","off=+0x98"]},{"name":"WASM Entity #2","id":"0x03E02400","path":"HEAPF32[16255270] @ 0x03E02498","worldX":6990.0,"worldY":100.0,"worldZ":3325.0,"distanceToPlayer":31.9,"screenX":0,"screenY":0,"inViewport":true,"sampleKeys":["WASM_STRUCT","struct=0x03E02400","Δ(28.0, 0.0, 15.2)","off=+0x98"]}]}"""
        )
        assertEquals(2, vm.uiState.value.discoveredEntities.size)
        assertEquals(WizardStepBadge.CAPTURED, vm.uiState.value.step3Badge)

        // Live 500ms entity update moves Entity #1 closer
        vm.onToggleWasmEntitiesLiveRefresh()
        assertTrue(vm.uiState.value.isWasmEntitiesLivePolling)

        vm.onWasmEntitiesUpdatedFromBridge(
            """{"stage":"WASM_ENTITIES_TICK","tick":4,"entities":[{"name":"WASM Entity #1","id":"0x03E01000","path":"HEAPF32[16253990] @ 0x03E01098","worldX":6966.0,"worldY":100.0,"worldZ":3310.0,"distanceToPlayer":4.03,"screenX":0,"screenY":0,"inViewport":true,"sampleKeys":["WASM_STRUCT","struct=0x03E01000","Δ(4.0, 0.0, 0.2)"]}]}"""
        )
        val movedEntity = vm.uiState.value.discoveredEntities.first { it.id == "0x03E01000" }
        assertEquals(6966.0, movedEntity.worldX, 0.001)
        assertEquals(4.03, movedEntity.distanceToPlayer, 0.01)
        assertEquals(4, vm.uiState.value.wasmEntityLiveTickCount)
    }

    @Test
    fun `entity struct prober binds hp and mob names persists per site streams live hud vitals and exports`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ptrStore = SitePointerStore(InMemoryPointerStorage())
        val schemaStore = SiteStructSchemaStore(InMemoryPointerStorage())
        val vm = DiagnosticsViewModel()
        vm.setSitePointerStoreForTesting(ptrStore)
        vm.setSiteStructSchemaStoreForTesting(schemaStore)

        val url = "https://universe.flyff.com/play"
        vm.onUrlInputChanged(url)
        vm.onLoadUrlRequested()

        // Seed an active live player pointer
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_POINTER_SCAN_COMPLETE","durationMs":9.0,"targetByteAddress":65011712,"totalL1":1,"totalL2":0,"chains":[{"id":"L1_0x00135C64_+0x98","depth":1,"basePointerAddress":1268836,"offsets":[152],"resolvedTargetAddress":65011712,"resolvedX":6961.98,"resolvedY":100.0,"resolvedZ":3309.76,"isLikelyStatic":true,"isVerifiedLive":true,"expression":"*(0x00135C64) + 0x98"}]}"""
        )

        // 1. Probe HP value -> receive candidate with adjacent MaxHP and 3-mob cross-preview -> bind
        vm.onSelectStructProbeField(WasmStructFieldKind.HP)
        vm.onStructProbeInputChanged("4338")
        vm.runStructFieldProbe(refineExisting = false)
        assertTrue(vm.uiState.value.structProber.isProbing)

        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_STRUCT_PROBE_RESULTS","fieldKind":"HP","isRefine":false,"durationMs":1.4,"totalFound":1,"candidates":[{"id":"D_456_INT32","relOffsetBytes":456,"subOffsetBytes":null,"valueType":"INT32","expression":"+0x1C8 [i32]","playerDisplayValue":"4338","playerNumericValue":4338,"adjacentMaxOffsetBytes":460,"adjacentMaxValue":4500,"mobPreviews":["#1=280","#2=310"],"nonZeroMobCount":2}]}"""
        )
        assertFalse(vm.uiState.value.structProber.isProbing)
        assertEquals(1, vm.uiState.value.structProber.candidates.size)

        vm.onBindStructFieldCandidate(vm.uiState.value.structProber.candidates.first())
        val schemaAfterHp = vm.uiState.value.structProber.schema
        assertEquals(456, schemaAfterHp.hp?.relOffsetBytes)
        assertEquals(460, schemaAfterHp.maxHp?.relOffsetBytes)
        assertTrue(schemaAfterHp.isSavedLocally)

        // 2. Auto-probe Name -> receive CHAR_PTR candidate -> bind
        vm.runAutoProbeEntityNames()
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_STRUCT_PROBE_RESULTS","fieldKind":"NAME","isRefine":false,"durationMs":2.1,"totalFound":1,"candidates":[{"id":"N_528_CHAR_PTR","relOffsetBytes":528,"subOffsetBytes":null,"valueType":"CHAR_PTR","expression":"+0x210 [char*]","playerDisplayValue":"HeroBlade","mobPreviews":["#1=\"Small Aibatt\"","#2=\"Mushpang\""],"nonZeroMobCount":2}]}"""
        )
        vm.onBindStructFieldCandidate(vm.uiState.value.structProber.candidates.first())
        assertEquals(528, vm.uiState.value.structProber.schema.name?.relOffsetBytes)
        assertEquals(WasmStructValueType.CHAR_PTR, vm.uiState.value.structProber.schema.name?.valueType)

        // 3. Receive 200ms pointer watch tick containing live playerVitals -> populates HUD vitals
        vm.onWasmPointersUpdatedFromBridge(
            """{"stage":"WASM_POINTER_WATCH_TICK","tick":6,"activeHudPointerId":"L1_0x00135C64_+0x98","playerVitals":{"name":"HeroBlade","hp":4120,"maxHp":4500,"mp":820,"maxMp":900,"fp":510,"maxFp":600},"results":[{"id":"L1_0x00135C64_+0x98","resolvedTargetAddress":65011712,"resolvedX":6965.0,"resolvedY":100.0,"resolvedZ":3311.0,"status":"MOVING","isVerifiedLive":true,"changedFromPrev":true,"changeCount":2,"lastDeltaMagnitude":3.2}]}"""
        )
        val vitals = vm.uiState.value.playerVitals
        assertTrue(vitals != null)
        assertEquals("HeroBlade", vitals!!.playerName)
        assertEquals(4120, vitals.hp)
        assertEquals(4500, vitals.effectiveMaxHp)
        assertEquals(820, vitals.mp)
        assertEquals(510, vitals.fp)

        // 4. Receive 500ms live entity tick containing decoded Mob Name + HP
        vm.onWasmEntitiesUpdatedFromBridge(
            """{"stage":"WASM_ENTITIES_TICK","tick":2,"entities":[{"name":"WASM Entity #1","id":"0x03E01000","path":"HEAPF32[16253990] @ 0x03E01098","coordByteAddress":65015960,"heapIndex":16253990,"resolvedName":"Small Aibatt","hp":240,"maxHp":280,"mp":40,"maxMp":40,"worldX":6970.0,"worldY":100.0,"worldZ":3314.0,"distanceToPlayer":5.8,"screenX":0,"screenY":0,"inViewport":true,"sampleKeys":["WASM_STRUCT","struct=0x03E01000"]}]}"""
        )
        val mob = vm.uiState.value.discoveredEntities.first()
        assertEquals("Small Aibatt", mob.displayName)
        assertEquals(240, mob.hp)
        assertEquals(280, mob.maxHp)

        // 5. Verify fresh ViewModel reloads saved per-site struct schema on page finish
        val reloadedVm = DiagnosticsViewModel()
        reloadedVm.setSitePointerStoreForTesting(ptrStore)
        reloadedVm.setSiteStructSchemaStoreForTesting(schemaStore)
        reloadedVm.onPageFinished(url, "Flyff Universe")
        assertEquals(456, reloadedVm.uiState.value.structProber.schema.hp?.relOffsetBytes)
        assertEquals(528, reloadedVm.uiState.value.structProber.schema.name?.relOffsetBytes)

        // 6. Verify SharedPreferences constructor and export JSON / Log Dump
        val prefsSchemaStore = SiteStructSchemaStore(context)
        prefsSchemaStore.clearSchemaForUrl(url)
        prefsSchemaStore.saveSchemaForUrl(url, vm.uiState.value.structProber.schema)
        assertEquals(456, prefsSchemaStore.loadSchemaForUrl(url).hp?.relOffsetBytes)

        val mapJson = DiagnosticsViewModel.buildWasmMemoryMapJson(vm.uiState.value)
        assertTrue(mapJson.contains("entityStructSchema"))
        assertTrue(mapJson.contains("livePlayerVitals"))
        assertTrue(mapJson.contains("HeroBlade"))

        val dumpText = DiagnosticsViewModel.buildFullLogDumpText(vm.uiState.value)
        assertTrue(dumpText.contains("Small Aibatt"))
        assertTrue(dumpText.contains("HP=240/280"))
        assertTrue(dumpText.contains("BOUND ENTITY STRUCT SCHEMA"))
    }

    @Test
    fun `bookmarked pointers and struct offsets survive cold app restart via synchronous SharedPreferences and populate startup Saved Sites`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Ensure clean slate in Robolectric SharedPreferences
        SitePointerStore(context).clearPointersForHost("universe.flyff.com")
        SiteStructSchemaStore(context).clearSchemaForHost("universe.flyff.com")

        val siteUrl = "https://universe.flyff.com/play"

        // --- Session 1: Discover pointer, bookmark it, bind HP + Name offsets ---
        val session1Vm = DiagnosticsViewModel()
        session1Vm.attachContext(context)
        session1Vm.onUrlInputChanged(siteUrl)
        session1Vm.onLoadUrlRequested()
        session1Vm.onPageFinished(siteUrl, "Flyff Universe")

        session1Vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_POINTER_SCAN_COMPLETE","durationMs":10.0,"targetByteAddress":65011712,"totalL1":1,"totalL2":0,"chains":[{"id":"L1_0x00135C64_+0x98","depth":1,"basePointerAddress":1268836,"offsets":[152],"resolvedTargetAddress":65011712,"resolvedX":6961.98,"resolvedY":100.0,"resolvedZ":3309.76,"isLikelyStatic":true,"isVerifiedLive":true,"expression":"*(0x00135C64) + 0x98"}]}"""
        )
        session1Vm.onTogglePinWasmPointerChain("L1_0x00135C64_+0x98")
        assertEquals(1, session1Vm.uiState.value.wasmFloatDelta.savedPointerCountForHost)

        session1Vm.onSelectStructProbeField(WasmStructFieldKind.HP)
        session1Vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_STRUCT_PROBE_RESULTS","fieldKind":"HP","isRefine":false,"durationMs":1.2,"totalFound":1,"candidates":[{"id":"D_456_INT32","relOffsetBytes":456,"subOffsetBytes":null,"valueType":"INT32","expression":"+0x1C8 [i32]","playerDisplayValue":"4338","playerNumericValue":4338,"adjacentMaxOffsetBytes":460,"adjacentMaxValue":4500,"mobPreviews":["#1=280"],"nonZeroMobCount":1}]}"""
        )
        session1Vm.onBindStructFieldCandidate(session1Vm.uiState.value.structProber.candidates.first())

        // --- Session 2: Simulated cold app restart (brand new ViewModel on about:blank) ---
        val coldStartVm = DiagnosticsViewModel()
        coldStartVm.attachContext(context)

        // Immediately shows Saved Site profile on startup even before navigating away from about:blank
        assertEquals("about:blank", coldStartVm.uiState.value.activeUrl)
        val savedProfiles = coldStartVm.uiState.value.savedSiteProfiles
        assertEquals(1, savedProfiles.size)
        val flyffProfile = savedProfiles.first()
        assertEquals("universe.flyff.com", flyffProfile.hostKey)
        assertEquals(1, flyffProfile.pointerCount)
        assertEquals(2, flyffProfile.boundStructFieldCount) // HP + adjacent MaxHP
        assertTrue(flyffProfile.pointerExpressions.any { it.contains("*(0x00135C64) + 0x98") })

        // One-tap Open & Verify restores pointers + schema and queues URL load
        coldStartVm.onOpenSavedSiteProfile(flyffProfile)
        assertEquals(siteUrl, coldStartVm.uiState.value.urlInput)
        assertEquals(1, coldStartVm.uiState.value.wasmFloatDelta.pointerChains.size)
        assertEquals(456, coldStartVm.uiState.value.structProber.schema.hp?.relOffsetBytes)

        // Subdomain redirect on page finish still resolves saved data & triggers auto-verify
        coldStartVm.onPageFinished("https://play.universe.flyff.com/world", "Flyff World")
        assertTrue(coldStartVm.uiState.value.wasmFloatDelta.isAutoVerifyingOnReload)
        assertEquals(1, coldStartVm.uiState.value.wasmFloatDelta.pointerChains.size)
        assertEquals(456, coldStartVm.uiState.value.structProber.schema.hp?.relOffsetBytes)

        // Delete saved site clears both stores and updates startup list
        coldStartVm.onDeleteSavedSiteProfile("universe.flyff.com")
        assertTrue(coldStartVm.uiState.value.savedSiteProfiles.isEmpty())
        assertEquals(0, SitePointerStore(context).loadPointersForHost("universe.flyff.com").size)
        assertFalse(SiteStructSchemaStore(context).loadSchemaForHost("universe.flyff.com").hasAnyBound)
    }

    @Test
    fun `draggable split presets and 4-tab console navigation work cleanly`() {
        val vm = DiagnosticsViewModel()

        // Default tab is COORDS in balanced split mode
        assertEquals(ConsoleTab.COORDS, vm.uiState.value.activeConsoleTab)
        assertEquals(0.48f, vm.uiState.value.consoleSplitFraction, 0.001f)

        // Snap to Mini, Split, Full and clamp out-of-range drag values
        vm.onSnapConsoleMini()
        assertEquals(0.15f, vm.uiState.value.consoleSplitFraction, 0.001f)

        // Selecting a tab while in Mini mode auto-expands back to comfortable split height
        vm.onSelectConsoleTab(ConsoleTab.PROBER)
        assertEquals(ConsoleTab.PROBER, vm.uiState.value.activeConsoleTab)
        assertEquals(0.48f, vm.uiState.value.consoleSplitFraction, 0.001f)

        vm.onSelectProberSubTab(ProberSubTab.FULL_HEAP)
        assertEquals(ProberSubTab.FULL_HEAP, vm.uiState.value.activeProberSubTab)

        vm.onSelectConsoleTab(ConsoleTab.LOGS_ASSETS)
        vm.onSelectLogsAssetsSubTab(LogsAssetsSubTab.ASSETS)
        assertEquals(ConsoleTab.LOGS_ASSETS, vm.uiState.value.activeConsoleTab)
        assertEquals(LogsAssetsSubTab.ASSETS, vm.uiState.value.activeLogsAssetsSubTab)

        vm.onSnapConsoleFull()
        assertEquals(0.88f, vm.uiState.value.consoleSplitFraction, 0.001f)

        vm.onSetConsoleSplitFraction(0.02f)
        assertEquals(0.14f, vm.uiState.value.consoleSplitFraction, 0.001f)
        vm.onSetConsoleSplitFraction(0.99f)
        assertEquals(0.88f, vm.uiState.value.consoleSplitFraction, 0.001f)
    }

    @Test
    fun `site stores support registrable domain subdomain fallback and startup host listing`() {
        val ptrStore = SitePointerStore(InMemoryPointerStorage())
        val sampleChain = WasmPointerChain(
            id = "L1_0x00135C64_+0x98",
            depth = 1,
            basePointerAddress = 1268836L,
            offsets = listOf(152),
            resolvedTargetAddress = 65011712L,
            resolvedX = 6961.98,
            resolvedY = 100.0,
            resolvedZ = 3309.76,
            isLikelyStatic = true,
            isVerifiedLive = true,
            expression = "*(0x00135C64) + 0x98"
        )
        ptrStore.savePointerForUrl("https://universe.flyff.com/play", sampleChain)

        // Subdomain redirect falls back to registrable root domain
        val subdomainLoaded = ptrStore.loadPointersForUrl("https://play.universe.flyff.com/world1")
        assertEquals(1, subdomainLoaded.size)
        assertEquals("L1_0x00135C64_+0x98", subdomainLoaded.first().id)

        // Startup saved-sites listing returns metadata including lastUrl
        val savedSites = ptrStore.listSavedSites()
        assertEquals(1, savedSites.size)
        assertEquals("universe.flyff.com", savedSites.first().hostKey)
        assertEquals("https://universe.flyff.com/play", savedSites.first().lastUrl)

        // Struct schema store also resolves across subdomains
        val schemaStore = SiteStructSchemaStore(InMemoryPointerStorage())
        val schema = WasmEntityStructSchema(
            hp = WasmStructFieldOffset(456, null, WasmStructValueType.INT32, "+0x1C8 [i32]"),
            name = WasmStructFieldOffset(528, null, WasmStructValueType.CHAR_PTR, "+0x210 [char*]")
        )
        schemaStore.saveSchemaForUrl("https://universe.flyff.com/play", schema)
        val loadedSubdomainSchema = schemaStore.loadSchemaForUrl("https://play.universe.flyff.com/world1")
        assertEquals(456, loadedSubdomainSchema.hp?.relOffsetBytes)
        assertEquals(528, loadedSubdomainSchema.name?.relOffsetBytes)
        assertTrue(loadedSubdomainSchema.isSavedLocally)
    }

    @Test
    fun `selected target finder narrows slots watches live binds aggro assigns to entity list and toggles read-only location tracker`() {
        val targetPayloads = mutableListOf<String>()
        val bridge = TelemetryBridge(
            onTelemetryReceived = { _, _ -> },
            onReportPayloadReceived = {},
            onWasmTargetUpdated = { targetPayloads.add(it) }
        )
        bridge.updateWasmTarget("""{"stage":"WASM_TARGET_WATCH_TICK","tick":1,"hasTarget":true,"slotByteAddress":1270000,"slotHex":"0x001360F0","targetStructAddress":65015808,"targetCoordAddress":65015960}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, targetPayloads.size)

        val ptrStore = SitePointerStore(InMemoryPointerStorage())
        val schemaStore = SiteStructSchemaStore(InMemoryPointerStorage())
        val vm = DiagnosticsViewModel()
        vm.setSitePointerStoreForTesting(ptrStore)
        vm.setSiteStructSchemaStoreForTesting(schemaStore)

        val url = "https://universe.flyff.com/play"
        vm.onUrlInputChanged(url)
        vm.onLoadUrlRequested()

        // Seed player pointer so anchor exists
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_POINTER_SCAN_COMPLETE","durationMs":8.0,"targetByteAddress":65011712,"totalL1":1,"totalL2":0,"chains":[{"id":"L1_0x00135C64_+0x98","depth":1,"basePointerAddress":1268836,"offsets":[152],"resolvedTargetAddress":65011712,"resolvedX":6961.98,"resolvedY":100.0,"resolvedZ":3309.76,"isLikelyStatic":true,"isVerifiedLive":true,"expression":"*(0x00135C64) + 0x98"}]}"""
        )

        // 1. Capture Mob A -> Filter Different Mob -> Lock slot
        vm.onCaptureSelectedTarget()
        assertTrue(vm.uiState.value.targetScanner.isScanning)
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_TARGET_SCAN_RESULT","action":"CAPTURE","round":1,"durationMs":11.2,"totalCandidates":1240,"candidates":[]}"""
        )
        assertFalse(vm.uiState.value.targetScanner.isScanning)
        assertEquals(1, vm.uiState.value.targetScanner.scanRound)
        assertEquals(1240, vm.uiState.value.targetScanner.totalCandidates)

        vm.onFilterSelectedTarget(TargetScanFilterMode.CHANGED_NON_ZERO)
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_TARGET_SCAN_RESULT","action":"CHANGED_NON_ZERO","round":2,"durationMs":1.5,"totalCandidates":1,"candidates":[{"slotByteAddress":1270000,"slotHex":"0x001360F0","currentValue":65015808,"currentHex":"0x03E01000","isPointerLike":true,"nearPlayerOffsetBytes":64,"matchedEntityId":"0x03E01000","matchedEntityName":"Small Aibatt","resolvedCoordAddress":65015960,"resolvedX":6970.0,"resolvedY":100.0,"resolvedZ":3315.0,"previewName":"Small Aibatt","previewHp":240,"previewMaxHp":280,"previewAggroRaw":1,"score":1850}]}"""
        )
        assertEquals(1, vm.uiState.value.targetScanner.candidates.size)
        assertEquals(1270000L, vm.uiState.value.targetScanner.candidates.first().slotByteAddress)

        // 2. Lock target slot & receive 200ms live target watch tick
        vm.onLockSelectedTargetSlot(1270000L)
        assertTrue(vm.uiState.value.targetScanner.isWatchingTarget)
        assertEquals(1270000L, vm.uiState.value.targetScanner.lockedSlotAddress)

        vm.onWasmTargetUpdatedFromBridge(
            """{"stage":"WASM_TARGET_WATCH_TICK","tick":3,"slotByteAddress":1270000,"slotHex":"0x001360F0","targetStructAddress":65015808,"targetCoordAddress":65015960,"hasTarget":true,"matchedEntityId":"0x03E01000","resolvedName":"Small Aibatt","hp":210,"maxHp":280,"aggroRawValue":1,"isAggro":true,"worldX":6970.0,"worldY":100.0,"worldZ":3315.0,"distanceToPlayer":9.45}"""
        )
        val liveTarget = vm.uiState.value.targetScanner.liveTarget
        assertTrue(liveTarget != null && liveTarget.hasTarget)
        assertEquals("Small Aibatt", liveTarget!!.resolvedName)
        assertEquals(210, liveTarget.hp)
        assertEquals(280, liveTarget.maxHp)
        assertEquals(true, liveTarget.isAggro)

        // 3. Jump to Struct Prober anchored to Selected Target -> Probe Aggro -> Bind & persist per site
        vm.onProbeSelectedTargetInStructProber(WasmStructFieldKind.AGGRO)
        assertEquals(ProberSubTab.STRUCT_PROBER, vm.uiState.value.activeProberSubTab)
        assertEquals(WasmStructProbeAnchor.SELECTED_TARGET, vm.uiState.value.structProber.probeAnchor)
        assertEquals(WasmStructFieldKind.AGGRO, vm.uiState.value.structProber.activeField)

        vm.runAggroStructProbe(isAggroedNow = true, refineExisting = false)
        assertTrue(vm.uiState.value.structProber.isProbing)
        vm.onTelemetryFromBridge(
            "WASM",
            """{"stage":"WASM_STRUCT_PROBE_RESULTS","fieldKind":"AGGRO","isRefine":false,"durationMs":1.1,"totalFound":1,"candidates":[{"id":"AG_612_UINT8","relOffsetBytes":612,"subOffsetBytes":null,"valueType":"UINT8","expression":"+0x264 [u8]","playerDisplayValue":"AGGRO (1)","playerNumericValue":1,"mobPreviews":["#1=0","#2=1"],"nonZeroMobCount":1}]}"""
        )
        vm.onBindStructFieldCandidate(vm.uiState.value.structProber.candidates.first())
        assertEquals(612, vm.uiState.value.structProber.schema.aggro?.relOffsetBytes)
        assertEquals(WasmStructValueType.UINT8, vm.uiState.value.structProber.schema.aggro?.valueType)
        assertEquals(612, schemaStore.loadSchemaForUrl(url).aggro?.relOffsetBytes)

        // 4. Assign selected target to Entities list -> upserts entity with Name/HP/Aggro & activates Read-Only Location Tracker
        vm.onAssignSelectedTargetToEntityList()
        assertEquals(ConsoleTab.ENTITIES, vm.uiState.value.activeConsoleTab)
        val assigned = vm.uiState.value.discoveredEntities.firstOrNull { it.coordByteAddress == 65015960L }
        assertTrue(assigned != null)
        assertEquals("Small Aibatt", assigned!!.displayName)
        assertEquals(210, assigned.hp)
        assertEquals(true, assigned.isAggro)
        assertTrue(assigned.isActiveGameTarget)
        assertEquals(assigned.selectionKey, vm.uiState.value.trackedEntityKey)

        // 5. Tapping the tracked entity again toggles off the read-only location tracker
        vm.onToggleTrackEntityForLocationVerify(assigned.selectionKey)
        assertNull(vm.uiState.value.trackedEntityKey)
        vm.onToggleTrackEntityForLocationVerify(assigned.selectionKey)
        assertEquals(assigned.selectionKey, vm.uiState.value.trackedEntityKey)
        vm.onClearTrackedEntity()
        assertNull(vm.uiState.value.trackedEntityKey)
    }
}




