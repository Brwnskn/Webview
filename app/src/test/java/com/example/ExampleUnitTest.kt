package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun step2BaselineAndDeltaScriptsExcludeWasmHeapsAndTypedArrays() {
    val step2a = DiagnosticsScripts.buildCaptureBaselineScript()
    val step2b = DiagnosticsScripts.buildComputeDeltaScript()

    listOf(
      "HEAP8", "HEAPU8", "HEAP16", "HEAPU16", "HEAP32", "HEAPU32",
      "HEAPF32", "HEAPF64", "HEAP64", "HEAPU64", "wasmTableMirror"
    ).forEach { key ->
      assertTrue("Missing exclusion for $key in Step 2A", step2a.contains("'$key'"))
      assertTrue("Missing exclusion for $key in Step 2B", step2b.contains("'$key'"))
    }

    assertTrue(step2a.contains("const MAX_TOTAL_MS = 4.5;"))
    assertTrue(step2a.contains("const MAX_DEPTH = 2;"))
    assertTrue(step2a.contains("__isSafeBaselineObject"))
    assertTrue(step2a.contains("__isExcludedBinaryOrWasmObject"))
    assertTrue(step2b.contains("__isExcludedWasmKey(p)"))
    assertTrue(step2b.contains("__isSafeBaselineObject(parentObj)"))
  }

  @Test
  fun wasmHeapScriptsSupportNonBlockingHeap32HeapF32ScanDeltaFilterAndNeighborInspection() {
    val scanScript = WasmHeapScripts.buildInitialScanScript(4338.0)
    val filterScript = WasmHeapScripts.buildFilterDeltaScript(4120.0)
    val inspectScript = WasmHeapScripts.buildInspectNeighborsScript("HEAP32", 12345)
    val stopScript = WasmHeapScripts.buildStopNeighborPollScript()
    val resetScript = WasmHeapScripts.buildResetTrackerScript()

    // Non-blocking chunked scanning across HEAP32 and HEAPF32
    assertTrue(scanScript.contains("(async function()"))
    assertTrue(scanScript.contains("setTimeout(resolve, 12)"))
    assertTrue(scanScript.contains("obj.HEAP32"))
    assertTrue(scanScript.contains("obj.HEAPF32"))
    assertTrue(scanScript.contains("WASM_HEAP_SCAN_COMPLETE"))
    assertTrue(scanScript.contains("4338.0"))

    // Value-Delta filter narrows stored candidates and auto-starts ±16B neighbor polling
    assertTrue(filterScript.contains("WASM_HEAP_DELTA_FILTERED"))
    assertTrue(filterScript.contains("__startWasmNeighborPoll"))
    assertTrue(filterScript.contains("4120.0"))

    // Neighboring Memory Inspector reads ±4..±16 byte offsets (step -4..+4) every 200ms
    assertTrue(inspectScript.contains("for (let step = -4; step <= 4; step++)"))
    assertTrue(inspectScript.contains("updateWasmNeighbors"))
    assertTrue(inspectScript.contains("setInterval(pushSnapshot, 200)"))
    assertTrue(inspectScript.contains("12345"))

    // Strictly read-only: never writes to heap indices (assignment '=' not followed by '=')
    val heapWriteRegex = Regex("""(h32|hf32|HEAP32|HEAPF32)\[[^\]]+]\s*=(?!=)""")
    assertFalse(heapWriteRegex.containsMatchIn(scanScript))
    assertFalse(heapWriteRegex.containsMatchIn(filterScript))
    assertFalse(heapWriteRegex.containsMatchIn(inspectScript))

    assertTrue(stopScript.contains("__stopWasmNeighborPoll()"))
    assertTrue(resetScript.contains("__wasm.candidates32 = []"))
  }

  @Test
  fun wasmHexAddressFormattingAndTargetInputSanitizationWorkWithoutAndroidRuntime() {
    assertEquals("0x00000000", DiagnosticsViewModel.formatHexAddress(0L))
    assertEquals("0x00008000", DiagnosticsViewModel.formatHexAddress(32768L))
    assertEquals("0x00000000", DiagnosticsViewModel.formatHexAddress(-4L))

    val vm = DiagnosticsViewModel()
    vm.onWasmTargetValueChanged("HP: 4338.5abc")
    assertEquals("4338.5", vm.uiState.value.wasmHeap.targetValueInput)
  }

  @Test
  fun wasmFloatHeapDeltaScriptsAreNonBlockingMemorySafeAndReadOnly() {
    val baselineScript = WasmHeapScripts.buildFloatBaselineSnapshotScript()
    val deltaScript = WasmHeapScripts.buildFloatDeltaScanScript(minDelta = 0.05, maxDelta = 250.0)
    val pinScript = WasmHeapScripts.buildSyncPinnedFloatsScript(listOf(4096, 4097, 4098))
    val stopScript = WasmHeapScripts.buildStopFloatWatchScript()
    val resetScript = WasmHeapScripts.buildResetFloatDeltaScript()

    // Step 2A: non-blocking HEAPF32 baseline snapshot using bounded TypedArrays & standing jitter prune
    assertTrue(baselineScript.contains("(async function()"))
    assertTrue(baselineScript.contains("obj.HEAPF32"))
    assertTrue(baselineScript.contains("new Int32Array("))
    assertTrue(baselineScript.contains("new Float32Array("))
    assertTrue(baselineScript.contains("const START_INDEX = len > 4096 ? 256 : 0;"))
    assertTrue(baselineScript.contains("const MAX_SNAPSHOT = 1500000;"))
    assertTrue(baselineScript.contains("await __yieldFloatSlice(12)"))
    assertTrue(baselineScript.contains("WASM_FLOAT_BASELINE_CAPTURED"))

    // Step 2B: movement delta range, positional Vec3 clustering, and 200ms Live Float Watcher
    assertTrue(deltaScript.contains("(async function()"))
    assertTrue(deltaScript.contains("absDiff >= MIN_DELTA && absDiff <= MAX_DELTA"))
    assertTrue(deltaScript.contains("WASM_FLOAT_DELTA_SCANNED"))
    assertTrue(deltaScript.contains("updateWasmFloatCandidates"))
    assertTrue(deltaScript.contains("setInterval(pushWatchTick, 200)"))
    assertTrue(deltaScript.contains("['X', 'Y', 'Z']"))

    // Pin sync, stop, and reset
    assertTrue(pinScript.contains("__fd.pinnedIndices = [4096,4097,4098]"))
    assertTrue(stopScript.contains("__stopFloatWatch()"))
    assertTrue(resetScript.contains("__fd.snapIndices = null"))

    // Strictly read-only on HEAPF32
    val floatHeapWriteRegex = Regex("""(hf32|liveHf32|HEAPF32)\[[^\]]+]\s*=(?!=)""")
    assertFalse(floatHeapWriteRegex.containsMatchIn(baselineScript))
    assertFalse(floatHeapWriteRegex.containsMatchIn(deltaScript))
    assertFalse(floatHeapWriteRegex.containsMatchIn(pinScript))
  }

  @Test
  fun wasmLockCoordsAndPersistentPointerScanScriptsAreNonBlockingAndReadOnly() {
    val lockScript = WasmHeapScripts.buildLockFloatCoordsScript(xIndex = 5000, yIndex = 5001, zIndex = 5002)
    val unlockScript = WasmHeapScripts.buildUnlockFloatCoordsScript()
    val pointerScanScript = WasmHeapScripts.buildPointerScanScript(targetByteAddress = 20000L, maxStructOffset = 256)
    val verifyScript = WasmHeapScripts.buildVerifyPointerChainsScript(
      """[{"id":"L1_0x00001000_+0x10","basePointerAddress":4096,"offsets":[16]}]"""
    )

    // Lock / unlock coordinate scripts
    assertTrue(lockScript.contains("__fd.locked = { xIndex: 5000, yIndex: 5001, zIndex: 5002 };"))
    assertTrue(lockScript.contains("__startFloatWatch()"))
    assertTrue(lockScript.contains("updatePlayerCoords"))
    assertTrue(unlockScript.contains("__fd.locked = null;"))

    // Non-blocking Level-1 & Level-2 Uint32Array pointer scanner
    assertTrue(pointerScanScript.contains("(async function()"))
    assertTrue(pointerScanScript.contains("new Uint32Array(hf32.buffer)"))
    assertTrue(pointerScanScript.contains("const TARGET_ADDR = 20000;"))
    assertTrue(pointerScanScript.contains("const MAX_OFFSET = 256;"))
    assertTrue(pointerScanScript.contains("await __yieldFloatSlice(12)"))
    assertTrue(pointerScanScript.contains("WASM_POINTER_SCAN_COMPLETE"))
    assertTrue(pointerScanScript.contains("totalL1"))
    assertTrue(pointerScanScript.contains("totalL2"))

    // Live pointer chain verification
    assertTrue(verifyScript.contains("WASM_POINTER_CHAINS_VERIFIED"))
    assertTrue(verifyScript.contains("new Uint32Array(hf32.buffer)"))
    assertTrue(verifyScript.contains("isVerifiedLive"))

    // Strictly read-only: zero writes to HEAPF32 or HEAPU32
    val readOnlyRegex = Regex("""(hf32|hu32|HEAPF32|HEAPU32)\[[^\]]+]\s*=(?!=)""")
    assertFalse(readOnlyRegex.containsMatchIn(lockScript))
    assertFalse(readOnlyRegex.containsMatchIn(unlockScript))
    assertFalse(readOnlyRegex.containsMatchIn(pointerScanScript))
    assertFalse(readOnlyRegex.containsMatchIn(verifyScript))
    assertTrue(verifyScript.contains("status = 'ZERO'"))
  }

  @Test
  fun wasmPointerWatchAutoVerifyAndNearbyEntityScriptsAreNonBlockingAndStrictlyReadOnly() {
    val sampleChainsJson =
      """[{"id":"L1_0x00135C64_+0x98","basePointerAddress":1268836,"offsets":[152]}]"""
    val watchScript = WasmPointerAndEntityScripts.buildStartPointerWatchScript(
      chainsJsonArray = sampleChainsJson,
      hudPointerId = "L1_0x00135C64_+0x98"
    )
    val setHudScript = WasmPointerAndEntityScripts.buildSetHudPointerScript("L1_0x00135C64_+0x98")
    val stopWatchScript = WasmPointerAndEntityScripts.buildStopPointerWatchScript()
    val autoVerifyScript = WasmPointerAndEntityScripts.buildAutoVerifySavedPointersScript(sampleChainsJson)
    val entityScanScript = WasmPointerAndEntityScripts.buildScanWasmNearbyEntitiesScript(
      playerByteAddress = 1048576L,
      playerX = 6961.98,
      playerY = 100.0,
      playerZ = 3309.76
    )
    val entityLiveScript = WasmPointerAndEntityScripts.buildStartWasmEntitiesLiveRefreshScript()
    val entityStopScript = WasmPointerAndEntityScripts.buildStopWasmEntitiesLiveRefreshScript()

    // 200ms Live Pointer Watch + 4-state verdict + optional HUD stream
    assertTrue(watchScript.contains("setInterval(pushPtrTick, 200)"))
    assertTrue(watchScript.contains("updateWasmPointers"))
    assertTrue(watchScript.contains("updatePlayerCoords"))
    assertTrue(watchScript.contains("status = 'MOVING'"))
    assertTrue(watchScript.contains("status: isZero ? 'ZERO' : 'LIVE'"))
    assertTrue(watchScript.contains("status: 'STALE'"))
    assertTrue(setHudScript.contains("hudPointerId"))
    assertTrue(stopWatchScript.contains("clearInterval"))

    // Async non-blocking auto-verify after reload
    assertTrue(autoVerifyScript.contains("(async function()"))
    assertTrue(autoVerifyScript.contains("WASM_POINTER_AUTO_VERIFY_DONE"))
    assertTrue(autoVerifyScript.contains("__peYield(800)"))

    // Non-blocking 1 MB sliced WASM nearby-entity scan & 500ms live refresh
    assertTrue(entityScanScript.contains("(async function()"))
    assertTrue(entityScanScript.contains("WASM_ENTITIES_DISCOVERED"))
    assertTrue(entityScanScript.contains("const CHUNK_FLOATS = 262144;"))
    assertTrue(entityLiveScript.contains("setInterval(pushEntityTick, 500)"))
    assertTrue(entityLiveScript.contains("updateWasmEntities"))
    assertTrue(entityStopScript.contains("clearInterval"))

    // Strictly read-only: zero writes to HEAPF32 / HEAPU32 / HEAP32 and zero synthetic input events
    val heapWriteRegex = Regex("""(hf32|hu32|h32|HEAPF32|HEAPU32|HEAP32)\[[^\]]+]\s*=(?!=)""")
    val syntheticInputRegex = Regex("""(dispatchEvent|MouseEvent|PointerEvent|TouchEvent|KeyboardEvent)""")
    listOf(
      watchScript, setHudScript, stopWatchScript, autoVerifyScript,
      entityScanScript, entityLiveScript, entityStopScript
    ).forEach { script ->
      assertFalse("Must not write to WASM heap", heapWriteRegex.containsMatchIn(script))
      assertFalse("Must not inject synthetic input", syntheticInputRegex.containsMatchIn(script))
    }

    // Host normalization for per-site persistence
    assertEquals("universe.flyff.com", SitePointerStore.normalizeHostKey("https://Universe.Flyff.com/play?world=1"))
    assertEquals("example.com", SitePointerStore.normalizeHostKey("example.com/game"))
    assertNull(SitePointerStore.normalizeHostKey("about:blank"))
    assertNull(SitePointerStore.normalizeHostKey("   "))
  }

  @Test
  fun wasmStructProberScriptsCoverStatsNamesAndRemainStrictlyReadOnly() {
    val syncScript = WasmStructProberScripts.buildSyncStructSchemaScript("""{"hp":{"relOffsetBytes":456,"valueType":"INT32"}}""")
    val statProbeScript = WasmStructProberScripts.buildProbeStatOffsetsScript(
      fieldKind = WasmStructFieldKind.HP,
      playerByteAddress = 65011712L,
      targetValue = 4338.0
    )
    val statRefineScript = WasmStructProberScripts.buildProbeStatOffsetsScript(
      fieldKind = WasmStructFieldKind.HP,
      playerByteAddress = 65011712L,
      targetValue = 4120.0,
      existingCandidatesJson = """[{"relOffsetBytes":456,"valueType":"INT32"}]"""
    )
    val nameProbeScript = WasmStructProberScripts.buildProbeNameOffsetsScript(
      playerByteAddress = 65011712L,
      nameQuery = "Aibatt"
    )

    assertTrue(syncScript.contains("window.__wvStructSchema"))
    assertTrue(statProbeScript.contains("WASM_STRUCT_PROBE_RESULTS"))
    assertTrue(statProbeScript.contains("mobPreviews"))
    assertTrue(statProbeScript.contains("adjacentMaxOffsetBytes"))
    assertTrue(statRefineScript.contains("isRefine: true"))
    assertTrue(nameProbeScript.contains("INLINE_UTF8"))
    assertTrue(nameProbeScript.contains("CHAR_PTR"))
    assertTrue(nameProbeScript.contains("STD_STRING"))
    assertTrue(nameProbeScript.contains("Aibatt"))

    // Strictly read-only: zero writes to WASM linear memory and zero synthetic input events
    val heapWriteRegex = Regex("""(hf32|hu32|h32|h16|u8|heapF32|heapU32|heap32|heap16|heapU8|HEAPF32|HEAPU32|HEAP32|HEAPU8)\[[^\]]+]\s*=(?!=)""")
    val syntheticInputRegex = Regex("""(dispatchEvent|MouseEvent|PointerEvent|TouchEvent|KeyboardEvent)""")
    listOf(syncScript, statProbeScript, statRefineScript, nameProbeScript).forEach { script ->
      assertFalse("Must not write to WASM heap", heapWriteRegex.containsMatchIn(script))
      assertFalse("Must not inject synthetic input", syntheticInputRegex.containsMatchIn(script))
    }
  }

  @Test
  fun registrableDomainExtractionNormalizesSubdomainsAndMultiPartTlds() {
    assertEquals("flyff.com", SitePointerStore.extractRegistrableDomain("universe.flyff.com"))
    assertEquals("flyff.com", SitePointerStore.extractRegistrableDomain("play.universe.flyff.com"))
    assertEquals("example.co.uk", SitePointerStore.extractRegistrableDomain("game.example.co.uk"))
    assertNull(SitePointerStore.extractRegistrableDomain("localhost"))
  }

  @Test
  fun selectedTargetAndAggroProberScriptsAreNonBlockingAndStrictlyReadOnly() {
    val captureScript = WasmSelectedTargetScripts.buildCaptureSelectedTargetScript(65011712L)
    val filterDiffScript = WasmSelectedTargetScripts.buildFilterSelectedTargetScript(
      mode = TargetScanFilterMode.CHANGED_NON_ZERO,
      playerByteAddress = 65011712L
    )
    val filterDeselectScript = WasmSelectedTargetScripts.buildFilterSelectedTargetScript(
      mode = TargetScanFilterMode.DESELECTED_ZERO,
      playerByteAddress = 65011712L
    )
    val filterSameTypeScript = WasmSelectedTargetScripts.buildFilterSelectedTargetScript(
      mode = TargetScanFilterMode.SAME_TYPE_RESELECT,
      playerByteAddress = 65011712L
    )
    val lockWatchScript = WasmSelectedTargetScripts.buildLockTargetSlotScript(
      slotByteAddress = 1270000L,
      playerByteAddress = 65011712L
    )
    val registerEntityScript = WasmSelectedTargetScripts.buildRegisterTargetInTrackedEntitiesScript(
      coordByteAddress = 65015960L,
      structByteAddress = 65015808L,
      label = "Small Aibatt"
    )
    val stopScript = WasmSelectedTargetScripts.buildStopTargetWatchScript()
    val resetScript = WasmSelectedTargetScripts.buildResetTargetScanScript()

    val aggroPassiveScript = WasmStructProberScripts.buildProbeAggroOffsetsScript(
      targetAnchorByteAddress = 65015960L,
      playerByteAddress = 65011712L,
      isAggroedNow = false
    )
    val aggroActiveRefineScript = WasmStructProberScripts.buildProbeAggroOffsetsScript(
      targetAnchorByteAddress = 65015960L,
      playerByteAddress = 65011712L,
      isAggroedNow = true,
      existingCandidatesJson = """[{"relOffsetBytes":612,"valueType":"UINT8"}]"""
    )

    // Non-blocking async capture & differential filtering
    assertTrue(captureScript.contains("(async function()"))
    assertTrue(captureScript.contains("WASM_TARGET_SCAN_RESULT"))
    assertTrue(filterDiffScript.contains("CHANGED_NON_ZERO"))
    assertTrue(filterDeselectScript.contains("DESELECTED_ZERO"))
    assertTrue(filterSameTypeScript.contains("SAME_TYPE_RESELECT"))

    // 200ms live target watcher & local diagnostic entity registration
    assertTrue(lockWatchScript.contains("setInterval(pushTargetTick, 200)"))
    assertTrue(lockWatchScript.contains("updateWasmTarget"))
    assertTrue(lockWatchScript.contains("WASM_TARGET_WATCH_TICK"))
    assertTrue(registerEntityScript.contains("window.__wvWasmEntities"))
    assertTrue(registerEntityScript.contains("Small Aibatt"))
    assertTrue(stopScript.contains("clearInterval"))
    assertTrue(resetScript.contains("lockedSlotAddr = 0"))

    // 2-state Passive vs Aggroed struct probing
    assertTrue(aggroPassiveScript.contains("fieldKind: 'AGGRO'"))
    assertTrue(aggroActiveRefineScript.contains("isRefine: true"))

    // Strictly read-only: zero writes to WASM linear memory and zero synthetic input events
    val heapWriteRegex = Regex("""(hf32|hu32|h32|h16|u8|heapF32|heapU32|heap32|heap16|heapU8|HEAPF32|HEAPU32|HEAP32|HEAPU8)\[[^\]]+]\s*=(?!=)""")
    val syntheticInputRegex = Regex("""(dispatchEvent|MouseEvent|PointerEvent|TouchEvent|KeyboardEvent)""")
    listOf(
      captureScript,
      filterDiffScript,
      filterDeselectScript,
      filterSameTypeScript,
      lockWatchScript,
      registerEntityScript,
      stopScript,
      resetScript,
      aggroPassiveScript,
      aggroActiveRefineScript
    ).forEach { script ->
      assertFalse("Must not write to WASM heap", heapWriteRegex.containsMatchIn(script))
      assertFalse("Must not inject synthetic input", syntheticInputRegex.containsMatchIn(script))
    }
  }
}




