package com.example

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ln

/**
 * Observational binary asset mirror & read-only ByteBuffer metadata auditor.
 *
 * Saves copies of intercepted `.bin`, `.wasm`, and `.json` assets into private app storage
 * (`context.filesDir/captured_assets`) without blocking or altering WebView network traffic,
 * then inspects headers, magic bytes, ByteBuffer structures, and entropy read-only.
 */
object BinaryAssetInspector {

    private const val CACHE_DIR_NAME = "captured_assets"
    private const val MAX_CAPTURE_BYTES = 4 * 1024 * 1024 // 4 MB safety cap per asset
    private const val MAX_CACHED_FILES = 40
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    private val CAPTURE_EXTENSIONS = setOf(".bin", ".wasm", ".json")

    fun shouldCaptureLocally(extension: String): Boolean {
        return extension.lowercase(Locale.US) in CAPTURE_EXTENSIONS
    }

    fun extractFileName(url: String, fallbackExt: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val segment = withoutQuery.substringAfterLast('/').trim()
        val candidate = if (segment.isNotBlank()) segment else "asset${fallbackExt.ifBlank { ".bin" }}"
        val sanitized = candidate.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return sanitized.ifBlank { "asset.bin" }
    }

    fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun clearLocalCache(context: Context): Int {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) return 0
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) {
                removed++
            }
        }
        return removed
    }

    /**
     * Passively fetches a copy of the observed `.bin`/`.wasm`/`.json` asset on a background thread,
     * persists it safely under `context.filesDir/captured_assets`, and runs read-only ByteBuffer inspection.
     */
    fun fetchSaveAndInspect(
        context: Context,
        asset: InterceptedAsset,
        userAgent: String,
        refererUrl: String?
    ): InterceptedAsset {
        val rawUrl = asset.url
        if (!rawUrl.startsWith("https://", ignoreCase = true) && !rawUrl.startsWith("http://", ignoreCase = true)) {
            return asset.copy(
                captureStatus = AssetCaptureStatus.FAILED,
                structuralSummary = "Skipped: unsupported URL scheme"
            )
        }

        var connection: HttpURLConnection? = null
        return try {
            val parsedUrl = URL(rawUrl)
            connection = (parsedUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                if (!refererUrl.isNullOrBlank() &&
                    (refererUrl.startsWith("https://", ignoreCase = true) || refererUrl.startsWith("http://", ignoreCase = true))
                ) {
                    setRequestProperty("Referer", refererUrl)
                }
            }

            val statusCode = connection.responseCode
            val contentType = connection.contentType?.substringBefore(';')?.trim().orEmpty()
            if (statusCode !in 200..299) {
                return asset.copy(
                    captureStatus = AssetCaptureStatus.FAILED,
                    httpStatus = statusCode,
                    contentType = contentType,
                    structuralSummary = "HTTP $statusCode (not cached locally)"
                )
            }

            val stream = connection.inputStream
            val buffer = ByteArray(16 * 1024)
            val output = ByteArrayOutputStream()
            var totalRead = 0
            var truncated = false

            stream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    val remaining = MAX_CAPTURE_BYTES - totalRead
                    if (remaining <= 0) {
                        truncated = true
                        break
                    }
                    val toWrite = minOf(read, remaining)
                    output.write(buffer, 0, toWrite)
                    totalRead += toWrite
                    if (toWrite < read) {
                        truncated = true
                        break
                    }
                }
            }

            val bytes = output.toByteArray()
            val savedFile = saveBytesSafely(context, rawUrl, asset.fileName, bytes)
            val inspection = inspectBytes(
                bytes = bytes,
                extension = asset.extension,
                contentType = contentType,
                isTruncated = truncated
            )

            asset.copy(
                captureStatus = AssetCaptureStatus.CAPTURED,
                localRelativePath = "$CACHE_DIR_NAME/${savedFile.name}",
                httpStatus = statusCode,
                contentType = contentType,
                byteSize = bytes.size.toLong(),
                isTruncated = truncated,
                sha256Prefix = inspection.sha256Prefix,
                magicSignature = inspection.magicSignature,
                hexHeaderPreview = inspection.hexHeaderPreview,
                entropyBits = inspection.entropyBits,
                structuralSummary = inspection.structuralSummary,
                structuralDetails = inspection.structuralDetails
            )
        } catch (e: Exception) {
            asset.copy(
                captureStatus = AssetCaptureStatus.FAILED,
                structuralSummary = "Mirror audit error: ${e.javaClass.simpleName}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun saveBytesSafely(
        context: Context,
        url: String,
        fileName: String,
        bytes: ByteArray
    ): File {
        val dir = getCacheDir(context)
        pruneOldestIfNeeded(dir)

        val urlHash = sha256Hex(url.toByteArray(Charsets.UTF_8)).take(8)
        val safeBase = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(56).ifBlank { "asset.bin" }
        val target = File(dir, "${urlHash}_$safeBase")

        // Path-traversal invariant: verify canonical target remains inside private captured_assets dir
        val canonicalDir = dir.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget.path.startsWith(canonicalDir.path + File.separator)) {
            "Invalid cache path outside filesDir"
        }

        canonicalTarget.writeBytes(bytes)
        return canonicalTarget
    }

    private fun pruneOldestIfNeeded(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size < MAX_CACHED_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED_FILES + 1)
            .forEach { it.delete() }
    }

    data class ByteInspectionResult(
        val sha256Prefix: String,
        val magicSignature: String,
        val hexHeaderPreview: String,
        val entropyBits: Double,
        val structuralSummary: String,
        val structuralDetails: String
    )

    /**
     * Pure read-only ByteBuffer structural analyzer for `.wasm`, `.json`, and `.bin` payloads.
     */
    fun inspectBytes(
        bytes: ByteArray,
        extension: String,
        contentType: String = "",
        isTruncated: Boolean = false
    ): ByteInspectionResult {
        val shaPrefix = sha256Hex(bytes).take(16)
        val entropy = computeShannonEntropy(bytes)
        val hexHeader = formatHexAsciiHeader(bytes, maxBytes = 32)
        val truncNote = if (isTruncated) " [capped at 4MB]" else ""

        if (bytes.isEmpty()) {
            return ByteInspectionResult(
                sha256Prefix = shaPrefix,
                magicSignature = "Empty (0 B)",
                hexHeaderPreview = "(empty)",
                entropyBits = 0.0,
                structuralSummary = "Empty payload (0 bytes)",
                structuralDetails = "No bytes available to inspect."
            )
        }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // 1. WebAssembly Binary (\0asm)
        if (bytes.size >= 8 &&
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x61.toByte() &&
            bytes[2] == 0x73.toByte() &&
            bytes[3] == 0x6D.toByte()
        ) {
            return inspectWasmBuffer(bytes, bb, shaPrefix, hexHeader, entropy, truncNote)
        }

        // 2. Binary glTF (.glb / glTF)
        if (bytes.size >= 12 &&
            bytes[0] == 0x67.toByte() &&
            bytes[1] == 0x6C.toByte() &&
            bytes[2] == 0x54.toByte() &&
            bytes[3] == 0x46.toByte()
        ) {
            val version = bb.getInt(4)
            val declaredLength = bb.getInt(8)
            val summary = "glTF Binary v$version (declared ${formatBytes(declaredLength.toLong())})$truncNote"
            val details = buildString {
                appendLine("Format    : glTF 2.0 Binary Container (GLB)")
                appendLine("Version   : $version")
                appendLine("Total Len : $declaredLength bytes (captured ${bytes.size})")
                appendLine("Entropy   : ${String.format(Locale.US, "%.2f", entropy)} bits/byte")
            }.trimEnd()
            return ByteInspectionResult(
                sha256Prefix = shaPrefix,
                magicSignature = "GLB v$version",
                hexHeaderPreview = hexHeader,
                entropyBits = entropy,
                structuralSummary = summary,
                structuralDetails = details
            )
        }

        // 3. JSON Payload
        val trimmedFirstByte = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
        if (extension.equals(".json", ignoreCase = true) ||
            contentType.contains("json", ignoreCase = true) ||
            trimmedFirstByte == '{' ||
            trimmedFirstByte == '['
        ) {
            val jsonAttempt = tryInspectJson(bytes, shaPrefix, hexHeader, entropy, truncNote)
            if (jsonAttempt != null) return jsonAttempt
        }

        // 4. Compressed containers (GZIP / ZLIB / ZIP)
        val compressionMagic = detectCompressionMagic(bytes)

        // 5. Generic .bin / structured binary audit via ByteBuffer (Little-Endian)
        val u32Preview = buildList {
            val count = minOf(4, bytes.size / 4)
            for (i in 0 until count) {
                val unsignedVal = bb.getInt(i * 4).toLong() and 0xFFFFFFFFL
                add(unsignedVal)
            }
        }
        val f32Stats = analyzeFloat32Grid(bb, bytes.size)
        val asciiTokens = extractPrintableStrings(bytes, maxTokens = 6)

        val magic = compressionMagic ?: when {
            f32Stats.plausibleFloatRatio >= 0.65 && bytes.size >= 16 -> "Binary Float32 Buffer"
            entropy >= 7.5 -> "High-Entropy Binary"
            else -> "Raw Binary (.bin)"
        }

        val summary = buildString {
            append(magic)
            append(" • ")
            append(formatBytes(bytes.size.toLong()))
            if (f32Stats.plausibleFloatRatio >= 0.5 && f32Stats.sampleTriplet != null) {
                append(" • f32=${f32Stats.sampleTriplet}")
            } else if (u32Preview.isNotEmpty()) {
                append(" • u32[0]=${u32Preview.first()}")
            }
            append(truncNote)
        }

        val details = buildString {
            appendLine("Format    : $magic$truncNote")
            appendLine("Size      : ${bytes.size} bytes (${formatBytes(bytes.size.toLong())})")
            appendLine("Entropy   : ${String.format(Locale.US, "%.2f", entropy)} bits/byte")
            if (u32Preview.isNotEmpty()) {
                appendLine("u32 LE    : ${u32Preview.joinToString(", ")}")
            }
            appendLine(
                "Float32   : ${f32Stats.plausibleCount}/${f32Stats.totalSampled} plausible " +
                    "(${String.format(Locale.US, "%.0f%%", f32Stats.plausibleFloatRatio * 100.0)})" +
                    (f32Stats.sampleTriplet?.let { " • sample $it" } ?: "")
            )
            if (asciiTokens.isNotEmpty()) {
                appendLine("Strings   : ${asciiTokens.joinToString(", ")}")
            }
        }.trimEnd()

        return ByteInspectionResult(
            sha256Prefix = shaPrefix,
            magicSignature = magic,
            hexHeaderPreview = hexHeader,
            entropyBits = entropy,
            structuralSummary = summary,
            structuralDetails = details
        )
    }

    private fun inspectWasmBuffer(
        bytes: ByteArray,
        bb: ByteBuffer,
        shaPrefix: String,
        hexHeader: String,
        entropy: Double,
        truncNote: String
    ): ByteInspectionResult {
        val version = bb.getInt(4)
        val sections = mutableListOf<String>()
        var offset = 8
        var sectionCount = 0

        while (offset < bytes.size && sectionCount < 32) {
            val sectionId = bytes[offset].toInt() and 0xFF
            offset++
            val (sectionSize, nextOffset) = readUnsignedLeb128(bytes, offset)
            if (nextOffset < 0 || sectionSize < 0) break
            offset = nextOffset
            val name = wasmSectionName(sectionId)
            sections.add("$name(${formatBytes(sectionSize.toLong())})")
            sectionCount++
            if (sectionSize > bytes.size - offset) break
            offset += sectionSize
        }

        val summary = "WASM v$version • ${sections.size} sections • ${formatBytes(bytes.size.toLong())}$truncNote"
        val details = buildString {
            appendLine("Format    : WebAssembly Binary Module (\\0asm)$truncNote")
            appendLine("Version   : $version (uint32 LE)")
            appendLine("Size      : ${bytes.size} bytes (${formatBytes(bytes.size.toLong())})")
            appendLine("Entropy   : ${String.format(Locale.US, "%.2f", entropy)} bits/byte")
            if (sections.isNotEmpty()) {
                appendLine("Sections  : ${sections.joinToString(", ")}")
            }
        }.trimEnd()

        return ByteInspectionResult(
            sha256Prefix = shaPrefix,
            magicSignature = "WASM v$version",
            hexHeaderPreview = hexHeader,
            entropyBits = entropy,
            structuralSummary = summary,
            structuralDetails = details
        )
    }

    private fun tryInspectJson(
        bytes: ByteArray,
        shaPrefix: String,
        hexHeader: String,
        entropy: Double,
        truncNote: String
    ): ByteInspectionResult? {
        val text = runCatching {
            String(bytes, 0, minOf(bytes.size, 512 * 1024), Charsets.UTF_8).trim()
        }.getOrNull() ?: return null
        if (!text.startsWith("{") && !text.startsWith("[")) return null

        return try {
            if (text.startsWith("{")) {
                val obj = JSONObject(text)
                val keys = buildList {
                    val iter = obj.keys()
                    while (iter.hasNext() && size < 12) add(iter.next())
                }
                val totalKeys = obj.length()
                val summary = "JSONObject ($totalKeys keys: ${keys.take(4).joinToString(", ")})$truncNote"
                val details = buildString {
                    appendLine("Format    : JSON Object (UTF-8)$truncNote")
                    appendLine("Top Keys  : $totalKeys (${keys.joinToString(", ")})")
                    appendLine("Size      : ${bytes.size} bytes (${formatBytes(bytes.size.toLong())})")
                    appendLine("Entropy   : ${String.format(Locale.US, "%.2f", entropy)} bits/byte")
                }.trimEnd()
                ByteInspectionResult(
                    sha256Prefix = shaPrefix,
                    magicSignature = "JSON Object ($totalKeys keys)",
                    hexHeaderPreview = hexHeader,
                    entropyBits = entropy,
                    structuralSummary = summary,
                    structuralDetails = details
                )
            } else {
                val arr = JSONArray(text)
                val firstObj = arr.optJSONObject(0)
                val sampleKeys = buildList {
                    val iter = firstObj?.keys()
                    while (iter != null && iter.hasNext() && size < 8) add(iter.next())
                }
                val summary = "JSONArray (${arr.length()} items" +
                    (if (sampleKeys.isNotEmpty()) ", keys: ${sampleKeys.take(4).joinToString(", ")}" else "") +
                    ")$truncNote"
                val details = buildString {
                    appendLine("Format    : JSON Array (UTF-8)$truncNote")
                    appendLine("Items     : ${arr.length()}")
                    if (sampleKeys.isNotEmpty()) {
                        appendLine("Item Keys : ${sampleKeys.joinToString(", ")}")
                    }
                    appendLine("Size      : ${bytes.size} bytes (${formatBytes(bytes.size.toLong())})")
                }.trimEnd()
                ByteInspectionResult(
                    sha256Prefix = shaPrefix,
                    magicSignature = "JSON Array (${arr.length()})",
                    hexHeaderPreview = hexHeader,
                    entropyBits = entropy,
                    structuralSummary = summary,
                    structuralDetails = details
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class Float32Stats(
        val totalSampled: Int,
        val plausibleCount: Int,
        val plausibleFloatRatio: Double,
        val sampleTriplet: String?
    )

    private fun analyzeFloat32Grid(bb: ByteBuffer, totalBytes: Int): Float32Stats {
        val maxFloats = minOf(64, totalBytes / 4)
        if (maxFloats == 0) return Float32Stats(0, 0, 0.0, null)
        var plausible = 0
        val sample = mutableListOf<Float>()
        for (i in 0 until maxFloats) {
            val f = bb.getFloat(i * 4)
            if (f.isFinite() && (f == 0f || (kotlin.math.abs(f) in 1e-5f..1e6f))) {
                plausible++
                if (sample.size < 3) sample.add(f)
            }
        }
        val triplet = if (sample.size >= 3) {
            "(${String.format(Locale.US, "%.2f", sample[0])}, ${String.format(Locale.US, "%.2f", sample[1])}, ${String.format(Locale.US, "%.2f", sample[2])})"
        } else null
        return Float32Stats(
            totalSampled = maxFloats,
            plausibleCount = plausible,
            plausibleFloatRatio = plausible.toDouble() / maxFloats.toDouble(),
            sampleTriplet = triplet
        )
    }

    private fun detectCompressionMagic(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return when {
            b0 == 0x1F && b1 == 0x8B -> "GZIP Compressed"
            b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04 -> "ZIP Archive"
            b0 == 0x78 && (b1 == 0x01 || b1 == 0x9C || b1 == 0xDA) -> "ZLIB Stream"
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> "PNG Image"
            b0 == 0x28 && b1 == 0xB5 && b2 == 0x2F && b3 == 0xFD -> "Zstandard (zstd)"
            else -> null
        }
    }

    private fun readUnsignedLeb128(bytes: ByteArray, startOffset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var idx = startOffset
        while (idx < bytes.size && shift <= 28) {
            val b = bytes[idx].toInt() and 0xFF
            idx++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) {
                return result to idx
            }
            shift += 7
        }
        return -1 to -1
    }

    private fun wasmSectionName(id: Int): String = when (id) {
        0 -> "Custom"
        1 -> "Type"
        2 -> "Import"
        3 -> "Function"
        4 -> "Table"
        5 -> "Memory"
        6 -> "Global"
        7 -> "Export"
        8 -> "Start"
        9 -> "Element"
        10 -> "Code"
        11 -> "Data"
        12 -> "DataCount"
        else -> "Sec#$id"
    }

    private fun extractPrintableStrings(bytes: ByteArray, maxTokens: Int): List<String> {
        val limit = minOf(bytes.size, 8192)
        val tokens = LinkedHashSet<String>()
        val current = StringBuilder()
        for (i in 0 until limit) {
            val c = bytes[i].toInt() and 0xFF
            if (c in 32..126) {
                current.append(c.toChar())
            } else {
                if (current.length in 4..28) {
                    val token = current.toString().trim()
                    if (token.length >= 4) tokens.add(token)
                    if (tokens.size >= maxTokens) break
                }
                current.setLength(0)
            }
        }
        if (tokens.size < maxTokens && current.length in 4..28) {
            val token = current.toString().trim()
            if (token.length >= 4) tokens.add(token)
        }
        return tokens.toList()
    }

    private fun formatHexAsciiHeader(bytes: ByteArray, maxBytes: Int): String {
        val count = minOf(bytes.size, maxBytes)
        val hexPart = buildString {
            for (i in 0 until count) {
                if (i > 0) append(' ')
                append(String.format(Locale.US, "%02X", bytes[i].toInt() and 0xFF))
            }
        }
        val asciiPart = buildString {
            for (i in 0 until count) {
                val v = bytes[i].toInt() and 0xFF
                append(if (v in 32..126) v.toChar() else '.')
            }
        }
        return "$hexPart\nASCII: $asciiPart"
    }

    private fun computeShannonEntropy(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val sampleSize = minOf(bytes.size, 65_536)
        val freq = IntArray(256)
        for (i in 0 until sampleSize) {
            freq[bytes[i].toInt() and 0xFF]++
        }
        var entropy = 0.0
        val total = sampleSize.toDouble()
        for (count in freq) {
            if (count > 0) {
                val p = count / total
                entropy -= p * (ln(p) / ln(2.0))
            }
        }
        return entropy
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { b ->
                append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
            }
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
