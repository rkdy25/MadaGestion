package mg.madagestion.core

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun `les montants sont arrondis à l'ariary`() {
        assertEquals(BigDecimal("1250"), Money.ar(BigDecimal("1249.5")))
        assertEquals(BigDecimal("1249"), Money.ar(BigDecimal("1249.49")))
    }

    @Test
    fun `les quantités gardent trois décimales`() {
        assertEquals(BigDecimal("2.346"), Money.qty(BigDecimal("2.3456")))
    }

    @Test
    fun `le format sépare les milliers par une espace`() {
        assertEquals("1 250 000", BigDecimal("1250000").fmt())
        assertEquals("-5 000", BigDecimal("-5000").fmt())
        assertEquals("2,5", BigDecimal("2.500").fmtQty())
    }

    @Test
    fun `l'égalité ignore l'échelle`() {
        assertTrue(BigDecimal("10") sameAs BigDecimal("10.00"))
        assertFalse(BigDecimal("10") sameAs BigDecimal("10.01"))
    }

    @Test
    fun `la somme additionne exactement`() {
        assertEquals(BigDecimal("0.3"), listOf("0.1", "0.2").sumBd { BigDecimal(it) })
    }
}
