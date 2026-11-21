package com.example.bsa.engine

import com.example.bsa.model.FileForensicAudit
import com.example.bsa.model.ForensicStatus
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Represents a single text cell positioned on a PDF page, extracted from PDFBox TextPosition runs.
 */
data class PdfCellSpan(
    val x: Float,
    val endX: Float,
    val y: Float,
    val text: String
)

/**
 * Represents a single visual horizontal row of cells on a PDF page.
 */
data class PdfVisualRow(
    val pageNumber: Int,
    val y: Float,
    val cells: List<PdfCellSpan>
) {
    fun joinedLine(): String = cells.joinToString("  ") { it.text.trim() }.trim()
}

data class ForensicExtractionResult(
    val audit: FileForensicAudit,
    val fullText: String,
    val pageTexts: List<String>,
    val visualRows: List<PdfVisualRow>
)

class PdfPasswordRequiredException(val fileName: String, message: String) : Exception(message)

class PdfIntegrityException(val fileName: String, message: String) : Exception(message)

/**
 * Module A: Original ePDF Forensic Gatekeeper (Android PDFBox implementation of gatekeeper.py)
 * Executes strictly on a background dispatcher (Dispatchers.Default) and performs:
 * 1. Raw PDF byte integrity checks (%PDF header magic, SHA-256 hash, %%EOF & /Prev revision count)
 * 2. In-memory password decryption & encryption status audit via pdfbox-android
 * 3. /Producer, /Creator, XMP stream metadata & CreationDate/ModDate tampering inspection
 * 4. Scanned / image-only PDF rejection (<150 native characters per page + PDImageXObject scan check)
 * 5. Single-pass coordinate-aware PDFBox table row extraction for IndianBankParser
 */
object ForensicGatekeeper {

    private const val MIN_NATIVE_CHARS_PER_PAGE = 150
    private const val MOD_DATE_TAMPER_THRESHOLD_MS = 5L * 60L * 1000L // 5 minutes

    private val SUSPICIOUS_TOOLS = listOf(
        "photoshop",
        "illustrator",
        "ilovepdf",
        "smallpdf",
        "foxit",
        "print to pdf",
        "microsoft print",
        "chrome",
        "skia/pdf",
        "libreoffice",
        "sejda",
        "canva",
        "nitro",
        "pdfescape",
        "sodapdf",
        "wps",
        "camscanner"
    )

    /**
     * Quickly probes raw PDF bytes in memory on a background thread to detect whether
     * a user password is required before full analysis begins (triggers password popup UI).
     */
    suspend fun isPdfPasswordProtected(pdfBytes: ByteArray): Boolean = withContext(Dispatchers.Default) {
        try {
            PDDocument.load(ByteArrayInputStream(pdfBytes), "").use { false }
        } catch (_: InvalidPasswordException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verifies whether the supplied password successfully unlocks the PDF strictly in memory.
     */
    suspend fun verifyPdfPassword(pdfBytes: ByteArray, password: String): Boolean = withContext(Dispatchers.Default) {
        try {
            PDDocument.load(ByteArrayInputStream(pdfBytes), password).use { true }
        } catch (_: InvalidPasswordException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun inspectAndExtract(
        fileName: String,
        pdfBytes: ByteArray,
        password: String
    ): ForensicExtractionResult = withContext(Dispatchers.Default) {
        inspectAndExtractBlocking(fileName, pdfBytes, password)
    }

    fun inspectAndExtractBlocking(
        fileName: String,
        pdfBytes: ByteArray,
        password: String
    ): ForensicExtractionResult {
        // 1. Raw file integrity validation (%PDF header signature & non-empty body)
        if (pdfBytes.size < 32) {
            throw PdfIntegrityException(fileName, "$fileName is empty or truncated (${pdfBytes.size} bytes).")
        }
        val headerPrefix = String(pdfBytes, 0, minOf(1024, pdfBytes.size), Charsets.ISO_8859_1)
        val pdfMagicIdx = headerPrefix.indexOf("%PDF-")
        if (pdfMagicIdx < 0) {
            throw PdfIntegrityException(fileName, "$fileName is not a valid PDF document (missing %PDF- header).")
        }
        val pdfVersionFromHeader = headerPrefix.substring(pdfMagicIdx + 5).take(3).trim().ifBlank { "1.4" }
        val sha256Short = computeSha256Short(pdfBytes)
        val eofCount = countAsciiMarker(pdfBytes, "%%EOF")
        val prevXrefCount = countAsciiMarker(pdfBytes, "/Prev")

        // 2. Password decryption & structural load strictly in memory
        val document = try {
            PDDocument.load(ByteArrayInputStream(pdfBytes), password)
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordRequiredException(
                fileName = fileName,
                message = if (password.isBlank()) {
                    "Password required to unlock $fileName"
                } else {
                    "Incorrect password for $fileName"
                }
            )
        } catch (e: IOException) {
            throw PdfIntegrityException(
                fileName = fileName,
                message = "Corrupted or unreadable PDF structure in $fileName: ${e.localizedMessage ?: "I/O error"}"
            )
        }

        document.use { doc ->
            val wasEncrypted = doc.isEncrypted
            if (wasEncrypted) {
                // Keep decrypted access strictly in memory; never persist unlocked bytes
                doc.isAllSecurityToBeRemoved = true
            }

            val pageCount = doc.numberOfPages
            if (pageCount <= 0) {
                throw PdfIntegrityException(fileName, "$fileName contains zero pages.")
            }

            val info = doc.documentInformation
            val producer = info?.producer?.trim().orEmpty().ifBlank { "Unspecified" }
            val creator = info?.creator?.trim().orEmpty().ifBlank { "Unspecified" }
            val pdfVersion = doc.version.takeIf { it > 0f }?.let { String.format(Locale.ROOT, "%.1f", it) }
                ?: pdfVersionFromHeader

            val creationCal = runCatching { info?.creationDate }.getOrNull()
            val modCal = runCatching { info?.modificationDate }.getOrNull()
            val creationDateStr = formatCal(creationCal)
            val modDateStr = formatCal(modCal)

            // Also inspect XMP metadata XML stream if present (editors sometimes leave traces here)
            val xmpText = extractXmpMetadataText(doc)

            // 3. Extract per-page native text, coordinate visual rows, and count embedded raster images
            val pageTexts = mutableListOf<String>()
            val allVisualRows = mutableListOf<PdfVisualRow>()
            var totalNativeChars = 0
            var totalImageObjects = 0

            val plainStripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }

            for (pageIdx in 1..pageCount) {
                // Count raster images on this page to identify scanned statements
                val pdPage = doc.getPage(pageIdx - 1)
                totalImageObjects += countPageImages(pdPage)

                plainStripper.startPage = pageIdx
                plainStripper.endPage = pageIdx
                val pageText = plainStripper.getText(doc).orEmpty()
                pageTexts.add(pageText)
                totalNativeChars += pageText.count { !it.isWhitespace() }

                // Extract coordinate-positioned rows using custom PDFBox TextPosition stripper
                val coordStripper = CoordinateRowStripper(pageNumber = pageIdx).apply {
                    sortByPosition = true
                    startPage = pageIdx
                    endPage = pageIdx
                }
                coordStripper.getText(doc)
                allVisualRows.addAll(coordStripper.buildVisualRows())
            }

            val avgCharsPerPage = totalNativeChars / pageCount
            val fullText = pageTexts.joinToString("\n")

            // 4. Evaluate all forensic gatekeeper rules
            val findings = mutableListOf<String>()
            var rejected = false
            var warning = false

            // Rule A: Scanned / image-only rejection (<150 native characters/page)
            if (avgCharsPerPage < MIN_NATIVE_CHARS_PER_PAGE) {
                rejected = true
                findings.add(
                    "Scanned or image-only PDF detected ($avgCharsPerPage native chars/page; minimum $MIN_NATIVE_CHARS_PER_PAGE required; $totalImageObjects raster image object(s))."
                )
            }

            // Rule B: /Producer, /Creator & XMP tamper/converter blocklist
            val combinedMetaLower = listOf(producer, creator, xmpText).joinToString(" ").lowercase(Locale.ROOT)
            val matchedTools = SUSPICIOUS_TOOLS.filter { tool -> combinedMetaLower.contains(tool) }
            if (matchedTools.isNotEmpty()) {
                rejected = true
                findings.add(
                    "Tampering/conversion tool detected in PDF metadata (${matchedTools.joinToString(", ")}) — Producer='$producer', Creator='$creator'."
                )
            }

            // Rule C: %%EOF markers & /Prev incremental revision audit
            if (eofCount > 2 || prevXrefCount > 1) {
                rejected = true
                findings.add(
                    "Post-generation incremental PDF revisions detected ($eofCount %%EOF markers, $prevXrefCount /Prev xref chain entries)."
                )
            } else if (eofCount == 2 || prevXrefCount == 1) {
                warning = true
                findings.add(
                    "Single incremental PDF update detected ($eofCount %%EOF markers) — verify bank digital signature."
                )
            }

            // Rule D: CreationDate vs ModDate post-generation edit check
            if (creationCal != null && modCal != null) {
                val deltaMs = modCal.timeInMillis - creationCal.timeInMillis
                if (deltaMs > MOD_DATE_TAMPER_THRESHOLD_MS) {
                    warning = true
                    findings.add(
                        "PDF Modification Date ($modDateStr) is later than Creation Date ($creationDateStr) — potential post-download edit."
                    )
                }
            }

            val status = when {
                rejected -> ForensicStatus.REJECTED
                warning -> ForensicStatus.WARNING
                else -> ForensicStatus.VERIFIED
            }

            if (findings.isEmpty()) {
                val encNote = if (wasEncrypted) "Unlocked in-memory (password-protected)" else "Unencrypted native ePDF"
                findings.add(
                    "$encNote verified: PDF v$pdfVersion, $avgCharsPerPage native chars/page, $eofCount %%EOF marker, clean Producer/Creator, SHA-256 $sha256Short."
                )
            }

            val audit = FileForensicAudit(
                fileName = fileName,
                detectedBank = "Detecting...",
                accountNumber = "-",
                statementPeriod = "-",
                pageCount = pageCount,
                avgCharsPerPage = avgCharsPerPage,
                producer = producer,
                creator = creator,
                eofMarkerCount = eofCount,
                status = status,
                findings = findings,
                isPasswordProtected = wasEncrypted,
                pdfVersion = pdfVersion,
                sha256Short = sha256Short,
                creationDate = creationDateStr,
                modDate = modDateStr,
                imageObjectCount = totalImageObjects
            )

            return ForensicExtractionResult(
                audit = audit,
                fullText = fullText,
                pageTexts = pageTexts,
                visualRows = allVisualRows
            )
        }
    }

    /**
     * Custom PDFBox TextPosition collector that clusters glyphs into visual cells and horizontal rows
     * using exact (x, y) coordinates so tabular Debit/Credit/Balance columns stay strictly aligned.
     */
    private class CoordinateRowStripper(private val pageNumber: Int) : PDFTextStripper() {

        private data class GlyphRun(val x: Float, val endX: Float, val y: Float, val text: String)

        private val runs = mutableListOf<GlyphRun>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isBlank() || textPositions.isEmpty()) return

            // Split a PDFBox string run into separate visual cells whenever horizontal gap > 1.8x space width
            val cellChars = StringBuilder()
            var cellStartX = textPositions.first().xDirAdj
            var cellEndX = cellStartX + textPositions.first().widthDirAdj
            var cellY = textPositions.first().yDirAdj

            for (i in textPositions.indices) {
                val tp = textPositions[i]
                val glyph = tp.unicode ?: continue
                if (i > 0) {
                    val prev = textPositions[i - 1]
                    val prevEnd = prev.xDirAdj + prev.widthDirAdj
                    val gap = tp.xDirAdj - prevEnd
                    val spaceThreshold = maxOf(prev.widthOfSpace * 1.9f, 7.5f)
                    if (gap > spaceThreshold || abs(tp.yDirAdj - cellY) > 3.0f) {
                        val flushed = cellChars.toString().trim()
                        if (flushed.isNotEmpty()) {
                            runs.add(GlyphRun(cellStartX, cellEndX, cellY, flushed))
                        }
                        cellChars.clear()
                        cellStartX = tp.xDirAdj
                        cellY = tp.yDirAdj
                    }
                }
                cellChars.append(glyph)
                cellEndX = tp.xDirAdj + tp.widthDirAdj
            }
            val tail = cellChars.toString().trim()
            if (tail.isNotEmpty()) {
                runs.add(GlyphRun(cellStartX, cellEndX, cellY, tail))
            }
        }

        fun buildVisualRows(): List<PdfVisualRow> {
            if (runs.isEmpty()) return emptyList()
            val sorted = runs.sortedWith(compareBy<GlyphRun> { it.y }.thenBy { it.x })
            val groupedRows = mutableListOf<MutableList<GlyphRun>>()
            val yTolerance = 2.6f

            for (run in sorted) {
                val lastGroup = groupedRows.lastOrNull()
                if (lastGroup != null && abs(lastGroup.first().y - run.y) <= yTolerance) {
                    lastGroup.add(run)
                } else {
                    groupedRows.add(mutableListOf(run))
                }
            }

            return groupedRows.map { group ->
                val ordered = group.sortedBy { it.x }
                // Merge adjacent fragments within the same column cell
                val mergedCells = mutableListOf<PdfCellSpan>()
                for (r in ordered) {
                    val prev = mergedCells.lastOrNull()
                    if (prev != null && (r.x - prev.endX) <= 6.5f) {
                        val spacer = if ((r.x - prev.endX) > 1.5f) " " else ""
                        mergedCells[mergedCells.lastIndex] = prev.copy(
                            endX = maxOf(prev.endX, r.endX),
                            text = "${prev.text}$spacer${r.text}".trim()
                        )
                    } else {
                        mergedCells.add(PdfCellSpan(r.x, r.endX, r.y, r.text))
                    }
                }
                PdfVisualRow(
                    pageNumber = pageNumber,
                    y = ordered.first().y,
                    cells = mergedCells
                )
            }
        }
    }

    private fun countPageImages(page: com.tom_roush.pdfbox.pdmodel.PDPage): Int {
        return runCatching {
            val resources = page.resources ?: return@runCatching 0
            var count = 0
            for (name in resources.xObjectNames) {
                val xObj = runCatching { resources.getXObject(name) }.getOrNull()
                if (xObj is PDImageXObject) count++
            }
            count
        }.getOrDefault(0)
    }

    private fun extractXmpMetadataText(doc: PDDocument): String {
        return runCatching {
            val stream = doc.documentCatalog?.metadata?.exportXMPMetadata() ?: return@runCatching ""
            stream.use { it.readBytes().toString(Charsets.UTF_8).take(16384) }
        }.getOrDefault("")
    }

    private fun computeSha256Short(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(6).joinToString("") { b -> String.format(Locale.ROOT, "%02X", b) }
    }

    private fun formatCal(cal: Calendar?): String {
        if (cal == null) return "-"
        return SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US).format(cal.time)
    }

    fun countEofMarkers(pdfBytes: ByteArray): Int = countAsciiMarker(pdfBytes, "%%EOF").coerceAtLeast(1)

    private fun countAsciiMarker(pdfBytes: ByteArray, asciiToken: String): Int {
        val marker = asciiToken.toByteArray(Charsets.ISO_8859_1)
        if (marker.isEmpty() || pdfBytes.size < marker.size) return 0
        var count = 0
        var i = 0
        val limit = pdfBytes.size - marker.size
        while (i <= limit) {
            var matched = true
            for (j in marker.indices) {
                if (pdfBytes[i + j] != marker[j]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                count++
                i += marker.size
            } else {
                i++
            }
        }
        return count
    }
}
