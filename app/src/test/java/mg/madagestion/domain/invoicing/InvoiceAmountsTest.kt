package mg.madagestion.domain.invoicing

import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.data.model.FeePayer
import mg.madagestion.data.model.Invoice
import mg.madagestion.data.model.InvoiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceAmountsTest {

    private fun invoice(fee: String = "0", paidBy: FeePayer? = null) = Invoice(
        id = "f1", companyId = "c", kind = InvoiceKind.SALE, thirdPartyId = "t",
        date = LocalDate.of(2026, 1, 10), dueDate = LocalDate.of(2026, 2, 10),
        totalHt = BigDecimal("100000"), totalVat = BigDecimal("20000"), totalTtc = BigDecimal("120000"),
        feeAmount = BigDecimal(fee), feePaidBy = paidBy
    )

    @Test
    fun `sans frais, le montant dû est le TTC`() {
        assertEquals(BigDecimal("120000"), invoice().amountDue())
    }

    @Test
    fun `des frais refacturés s'ajoutent au montant dû`() {
        val inv = invoice("15000", FeePayer.THIRD_PARTY)
        assertEquals(BigDecimal("135000"), inv.amountDue())
        assertEquals(BigDecimal("15000"), inv.feeOnThirdParty())
        assertEquals(BigDecimal.ZERO, inv.feeOnCompany())
    }

    @Test
    fun `des frais payés par l'entreprise ne changent pas le montant dû`() {
        val inv = invoice("15000", FeePayer.COMPANY)
        assertEquals(BigDecimal("120000"), inv.amountDue())
        assertEquals(BigDecimal("15000"), inv.feeOnCompany())
    }

    @Test
    fun `les avances sont prises des plus anciennes aux plus récentes`() {
        val parts = takeInOrder(
            listOf("a1" to BigDecimal("30000"), "a2" to BigDecimal("50000"), "a3" to BigDecimal("40000")),
            BigDecimal("70000")
        )
        assertEquals(listOf("a1" to BigDecimal("30000"), "a2" to BigDecimal("40000")), parts)
    }

    @Test
    fun `on ne prend pas plus que le disponible`() {
        val parts = takeInOrder(listOf("a1" to BigDecimal("30000")), BigDecimal("70000"))
        assertEquals(listOf("a1" to BigDecimal("30000")), parts)
    }

    @Test
    fun `rien à prendre si aucune avance ou montant nul`() {
        assertTrue(takeInOrder(emptyList(), BigDecimal("70000")).isEmpty())
        assertTrue(takeInOrder(listOf("a1" to BigDecimal("30000")), BigDecimal.ZERO).isEmpty())
        assertTrue(takeInOrder(listOf("a1" to BigDecimal.ZERO), BigDecimal("10")).isEmpty())
    }
}
