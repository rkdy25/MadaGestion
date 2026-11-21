package mg.madagestion.domain.report

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxWriterTest {

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun write(vararg sheets: XlsxSheet): Map<String, String> {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(sheets.toList(), out)
        return unzip(out.toByteArray())
    }

    @Test
    fun `les colonnes sont nommées comme dans Excel`() {
        assertEquals("A", XlsxWriter.columnName(0))
        assertEquals("Z", XlsxWriter.columnName(25))
        assertEquals("AA", XlsxWriter.columnName(26))
        assertEquals("AZ", XlsxWriter.columnName(51))
        assertEquals("BA", XlsxWriter.columnName(52))
    }

    @Test
    fun `le texte est échappé`() {
        assertEquals("A &amp; B &lt;C&gt; &quot;D&quot;", XlsxWriter.escape("A & B <C> \"D\""))
        assertEquals("ab", XlsxWriter.escape("a\u0001b"))
    }

    @Test
    fun `les noms de feuille sont valides et uniques`() {
        val names = XlsxWriter.uniqueNames(listOf("Balance", "balance", "A/B:C", "", "x".repeat(40)))
        assertEquals(listOf("Balance", "balance (2)", "A B C", "Feuille 4", "x".repeat(31)), names)
    }

    @Test
    fun `le classeur contient toutes ses parties`() {
        val parts = write(XlsxSheet("Un", listOf(10)), XlsxSheet("Deux", listOf(10)))
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"
            ),
            parts.keys
        )
        assertTrue(parts.getValue("xl/workbook.xml").contains("<sheet name=\"Deux\" sheetId=\"2\" r:id=\"rId2\"/>"))
    }

    @Test
    fun `les cellules portent leur type`() {
        val sheet = XlsxSheet("Test", listOf(10, 10, 10), frozenRows = 1)
        sheet.row(XlsxCell("Libellé", XlsxStyle.HEADER), XlsxCell(BigDecimal("1250000"), XlsxStyle.AMOUNT), XlsxCell(LocalDate.of(2026, 1, 1), XlsxStyle.DATE))
        sheet.row(XlsxCell(null), XlsxCell(""), XlsxCell(42))
        val xml = write(sheet).getValue("xl/worksheets/sheet1.xml")
        assertTrue(xml.contains("<c r=\"A1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Libellé</t></is></c>"))
        assertTrue(xml.contains("<c r=\"B1\" s=\"4\"><v>1250000</v></c>"))
        // 1er janvier 2026 = jour n° 46023 du calendrier d'Excel
        assertTrue(xml.contains("<c r=\"C1\" s=\"6\"><v>46023</v></c>"))
        assertTrue(xml.contains("<row r=\"2\"><c r=\"C2\" s=\"0\"><v>42</v></c></row>"))
        assertTrue(xml.contains("<pane ySplit=\"1\" topLeftCell=\"A2\""))
    }
}
