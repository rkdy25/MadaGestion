package mg.madagestion.domain.report

import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Mise en forme d'une cellule. L'ordre des valeurs correspond aux styles déclarés dans `styles.xml`. */
enum class XlsxStyle { NORMAL, BOLD, TITLE, HEADER, AMOUNT, AMOUNT_BOLD, DATE }

/** Cellule : texte ([String]), nombre ([BigDecimal], [Int], [Long]), date ([LocalDate]) ou vide (null). */
data class XlsxCell(val value: Any?, val style: XlsxStyle = XlsxStyle.NORMAL)

/**
 * Feuille de calcul.
 * @param columnWidths largeur de chaque colonne, en caractères.
 * @param frozenRows nombre de lignes d'en-tête qui restent visibles au défilement.
 */
class XlsxSheet(val name: String, val columnWidths: List<Int>, val frozenRows: Int = 0) {
    val rows = mutableListOf<List<XlsxCell>>()

    fun row(vararg cells: XlsxCell) {
        rows += cells.toList()
    }

    fun blank() {
        rows += emptyList<XlsxCell>()
    }
}

/**
 * Écrit un classeur Excel (.xlsx) sans bibliothèque externe : un fichier .xlsx est une archive ZIP
 * de quelques fichiers XML. Le résultat s'ouvre dans Excel, LibreOffice et Google Sheets.
 */
object XlsxWriter {

    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    fun write(sheets: List<XlsxSheet>, out: OutputStream) {
        require(sheets.isNotEmpty()) { "Un classeur contient au moins une feuille." }
        val names = uniqueNames(sheets.map { it.name })
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(sheets.size))
            zip.put("_rels/.rels", rootRels())
            zip.put("xl/workbook.xml", workbook(names))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            zip.put("xl/styles.xml", styles())
            sheets.forEachIndexed { i, sheet -> zip.put("xl/worksheets/sheet${i + 1}.xml", worksheet(sheet)) }
        }
    }

    private fun ZipOutputStream.put(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // ------------------------------------------------------------------
    // Parties fixes du classeur
    // ------------------------------------------------------------------

    private fun contentTypes(sheetCount: Int): String = buildString {
        append(XML)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (i in 1..sheetCount) {
            append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun rootRels(): String =
        XML + "<Relationships xmlns=\"$PKG_REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private fun workbook(names: List<String>): String = buildString {
        append(XML)
        append("<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\"><sheets>")
        names.forEachIndexed { i, name ->
            append("<sheet name=\"${escape(name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append(XML)
        append("<Relationships xmlns=\"$PKG_REL_NS\">")
        for (i in 1..sheetCount) {
            append("<Relationship Id=\"rId$i\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    /** Un `<xf>` par valeur de [XlsxStyle], dans le même ordre. Format 3 = « #,##0 », format 14 = date. */
    private fun styles(): String =
        XML + "<styleSheet xmlns=\"$MAIN_NS\">" +
            "<fonts count=\"4\">" +
            "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"14\"/><color rgb=\"FF102A43\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>" +
            "</fonts>" +
            "<fills count=\"3\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF102A43\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "</fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"7\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"3\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"

    // ------------------------------------------------------------------
    // Feuilles
    // ------------------------------------------------------------------

    private fun worksheet(sheet: XlsxSheet): String = buildString {
        append(XML)
        append("<worksheet xmlns=\"$MAIN_NS\">")
        append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
        append("<sheetViews><sheetView workbookViewId=\"0\">")
        if (sheet.frozenRows > 0) {
            append("<pane ySplit=\"${sheet.frozenRows}\" topLeftCell=\"A${sheet.frozenRows + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        }
        append("</sheetView></sheetViews>")
        if (sheet.columnWidths.isNotEmpty()) {
            append("<cols>")
            sheet.columnWidths.forEachIndexed { i, w ->
                append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        sheet.rows.forEachIndexed { r, cells ->
            append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { c, cell -> append(cellXml(columnName(c) + (r + 1), cell)) }
            append("</row>")
        }
        append("</sheetData>")
        // Impression : paysage, toutes les colonnes sur la largeur d'une page
        append("<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
        append("</worksheet>")
    }

    private fun cellXml(ref: String, cell: XlsxCell): String {
        val s = cell.style.ordinal
        return when (val v = cell.value) {
            null, "" -> if (s == 0) "" else "<c r=\"$ref\" s=\"$s\"/>"
            is BigDecimal -> "<c r=\"$ref\" s=\"$s\"><v>${v.toPlainString()}</v></c>"
            is Int, is Long -> "<c r=\"$ref\" s=\"$s\"><v>$v</v></c>"
            is LocalDate -> "<c r=\"$ref\" s=\"$s\"><v>${ChronoUnit.DAYS.between(EXCEL_EPOCH, v)}</v></c>"
            else -> "<c r=\"$ref\" s=\"$s\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(v.toString())}</t></is></c>"
        }
    }

    /** 0 -> A, 25 -> Z, 26 -> AA… */
    internal fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + n % 26)
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    /** Échappe le XML et retire les caractères de contrôle qu'il interdit. */
    internal fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    /** Excel refuse certains caractères, les noms de plus de 31 caractères et les doublons. */
    internal fun uniqueNames(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.mapIndexed { i, raw ->
            val clean = raw.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifBlank { "Feuille ${i + 1}" }
            var name = clean
            var n = 2
            while (!seen.add(name.lowercase())) {
                val suffix = " ($n)"
                name = clean.take(31 - suffix.length) + suffix
                n++
            }
            name
        }
    }
}
