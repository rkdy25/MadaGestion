package mg.madagestion.domain.control

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import mg.madagestion.core.fmt
import mg.madagestion.core.fmtQty
import mg.madagestion.core.sameAs
import mg.madagestion.core.sumBd
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.DocStatus
import mg.madagestion.data.model.EntryStatus
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.domain.accounting.Accounting
import mg.madagestion.domain.accounting.EntrySource
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.domain.invoicing.amountDue
import mg.madagestion.domain.invoicing.isSale
import mg.madagestion.domain.invoicing.label

enum class Severity { OK, INFO, WARN, ERROR }

data class Control(
    val title: String,
    val severity: Severity,
    val summary: String,
    val details: List<String> = emptyList()
)

/**
 * Contrôles d'intégrité et de cohérence, relancés à la demande depuis l'écran « Contrôles ».
 * Ils relisent toute la base : ils servent de filet de sécurité même si une règle a été contournée.
 */
class Controls(private val db: AppDatabase, private val accounting: Accounting) {

    private fun cap(list: List<String>, max: Int = 10): List<String> =
        if (list.size <= max) list else list.take(max) + "… et ${list.size - max} autre(s)"

    private fun result(title: String, problems: List<String>, severity: Severity, okText: String, failText: String) =
        if (problems.isEmpty()) Control(title, Severity.OK, okText)
        else Control(title, severity, "$failText (${problems.size})", cap(problems))

    suspend fun runAll(companyId: String): List<Control> {
        val c = companyId
        val today = LocalDate.now()
        val accounts = db.accountDao().list(c).associateBy { it.id }
        val entries = db.entryDao().listAll(c)
        val entryById = entries.associateBy { it.id }
        val lines = db.entryDao().validatedLines(c)
        val linesByEntry = lines.groupBy { it.entryId }
        val parties = db.thirdPartyDao().list(c).associateBy { it.id }
        val invoices = db.invoiceDao().list(c)
        val payments = db.paymentDao().list(c)
        val allocations = db.allocationDao().list(c)
        val products = db.productDao().list(c)
        val movements = db.stockDao().list(c)
        val periods = db.periodDao().list(c)
        val mappings = db.mappingDao().list(c).associate { it.mapKey to it.accountId }

        val validated = entries.filter { it.status == EntryStatus.VALIDATED }
        val out = mutableListOf<Control>()

        // 0. Configuration comptable
        run {
            val problems = MappingKey.values().mapNotNull { key ->
                val id = mappings[key.name]
                val a = id?.let { accounts[it] }
                when {
                    a == null -> "« ${key.label} » : aucun compte configuré"
                    !a.active -> "« ${key.label} » : le compte ${a.code} est désactivé"
                    else -> null
                }
            }
            out += result("Configuration comptable", problems, Severity.ERROR, "Tous les comptes de paramétrage sont configurés.", "Paramétrage incomplet")
        }

        // 1. Intégrité des écritures validées
        run {
            val problems = mutableListOf<String>()
            for (e in validated) {
                val ls = linesByEntry[e.id].orEmpty()
                val name = e.number ?: e.id.take(8)
                if (ls.size < 2) {
                    problems += "$name : moins de deux lignes"
                    continue
                }
                val d = ls.sumBd { it.debit }
                val k = ls.sumBd { it.credit }
                if (!d.sameAs(k)) problems += "$name : déséquilibrée (débit ${d.fmt()} / crédit ${k.fmt()})"
                else if (ls.any { it.debit.signum() < 0 || it.credit.signum() < 0 || (it.debit.signum() > 0 && it.credit.signum() > 0) || (it.debit.signum() == 0 && it.credit.signum() == 0) }) {
                    problems += "$name : ligne invalide (montant négatif, nul ou double sens)"
                }
                if (e.number == null) problems += "$name : écriture validée sans numéro"
            }
            out += result("Intégrité des écritures validées", problems, Severity.ERROR, "${validated.size} écriture(s) validée(s), toutes équilibrées.", "Écritures anormales")
        }

        // 2. Balance générale
        run {
            val d = lines.sumBd { it.debit }
            val k = lines.sumBd { it.credit }
            out += if (d.sameAs(k)) Control("Balance générale", Severity.OK, "Total débit = total crédit = ${d.fmt()} Ar.")
            else Control("Balance générale", Severity.ERROR, "Total débit ${d.fmt()} ≠ total crédit ${k.fmt()}.")
        }

        // 3. Numérotation sans trou
        run {
            val numbers = mutableListOf<String>()
            numbers += validated.mapNotNull { e -> e.number?.let { it } }
            numbers += invoices.mapNotNull { it.number }
            numbers += payments.map { it.number }
            val rx = Regex("^([A-Z]+)-(\\d{4})-(\\d+)$")
            val groups = HashMap<String, MutableSet<Int>>()
            for (n in numbers) {
                val m = rx.matchEntire(n) ?: continue
                groups.getOrPut("${m.groupValues[1]}-${m.groupValues[2]}") { mutableSetOf() }.add(m.groupValues[3].toInt())
            }
            val problems = mutableListOf<String>()
            for ((key, set) in groups.toSortedMap()) {
                val missing = (1..(set.maxOrNull() ?: 0)).filter { it !in set }
                if (missing.isNotEmpty()) problems += "$key : numéro(s) manquant(s) ${missing.take(5).joinToString(", ")}"
            }
            out += result("Numérotation sans trou", problems, Severity.ERROR, "Aucun trou dans les séquences de numéros.", "Séquences avec trous")
        }

        // 4. Doublons potentiels
        run {
            val problems = mutableListOf<String>()
            validated.filter { it.reversalOfId == null && it.reference.isNotBlank() }
                .groupBy { e ->
                    Triple(e.journalId + "|" + e.date, e.reference.trim().lowercase(), linesByEntry[e.id].orEmpty().sumBd { it.debit }.toPlainString())
                }
                .filter { it.value.size > 1 }
                .forEach { (_, es) -> problems += "Écritures ${es.joinToString(", ") { it.number ?: "?" }} : même journal, date, référence et montant" }
            invoices.filter { it.kind == InvoiceKind.PURCHASE && it.status == DocStatus.VALIDATED && it.reference.isNotBlank() }
                .groupBy { Triple(it.thirdPartyId, it.reference.trim().lowercase(), it.totalTtc.toPlainString()) }
                .filter { it.value.size > 1 }
                .forEach { (k, list) ->
                    problems += "Facture fournisseur « ${k.second} » de ${parties[k.first]?.name ?: "?"} saisie ${list.size} fois (${list.joinToString(", ") { it.number ?: "?" }})"
                }
            out += result("Doublons potentiels", problems, Severity.WARN, "Aucun doublon détecté.", "Doublons possibles")
        }

        // 5. Brouillons en attente
        run {
            val drafts = entries.filter { it.status == EntryStatus.DRAFT }
            val draftInvoices = invoices.filter { it.status == DocStatus.DRAFT }
            val problems = mutableListOf<String>()
            for (e in drafts) {
                val age = ChronoUnit.DAYS.between(e.date, today)
                val period = periods.firstOrNull { !e.date.isBefore(it.startDate) && !e.date.isAfter(it.endDate) }
                val label = e.label.ifBlank { e.reference.ifBlank { "écriture sans libellé" } }
                if (period?.closed == true) problems += "Brouillon « $label » (${e.date}) dans la période clôturée ${period.label}"
                else if (age > 30) problems += "Brouillon « $label » en attente depuis $age jours"
            }
            for (i in draftInvoices) {
                val age = ChronoUnit.DAYS.between(i.date, today)
                if (age > 30) problems += "${i.kind.label()} brouillon du ${i.date} en attente depuis $age jours"
            }
            val total = drafts.size + draftInvoices.size
            out += if (problems.isNotEmpty()) Control("Brouillons en attente", Severity.WARN, "Brouillons anciens ou bloqués (${problems.size})", cap(problems))
            else if (total > 0) Control("Brouillons en attente", Severity.INFO, "$total brouillon(s) à valider ou supprimer.")
            else Control("Brouillons en attente", Severity.OK, "Aucun brouillon en attente.")
        }

        // 6. Documents <-> écritures
        run {
            val problems = mutableListOf<String>()
            val customerAccounts = setOfNotNull(mappings[MappingKey.CUSTOMERS.name], mappings[MappingKey.CUSTOMER_ADVANCES.name])
            val supplierAccounts = setOfNotNull(mappings[MappingKey.SUPPLIERS.name], mappings[MappingKey.SUPPLIER_ADVANCES.name])
            for (inv in invoices.filter { it.status == DocStatus.VALIDATED }) {
                val name = inv.number ?: inv.id.take(8)
                val e = inv.entryId?.let { entryById[it] }
                if (e == null || e.status != EntryStatus.VALIDATED) {
                    problems += "$name : écriture comptable absente ou non validée"
                    continue
                }
                // Compte du tiers = collectif + avance déduite : le total doit égaler le montant dû
                val tiersAccounts = if (inv.kind.isSale()) customerAccounts else supplierAccounts
                if (tiersAccounts.isNotEmpty()) {
                    val ls = linesByEntry[e.id].orEmpty().filter { it.accountId in tiersAccounts && it.thirdPartyId == inv.thirdPartyId }
                    val net = ls.sumBd { it.debit }.subtract(ls.sumBd { it.credit })
                    val due = inv.amountDue()
                    val expected = if (inv.kind == InvoiceKind.SALE || inv.kind == InvoiceKind.PURCHASE_CREDIT) due else due.negate()
                    if (!net.sameAs(expected)) problems += "$name : montant au compte de tiers (${net.fmt()}) différent du montant dû (${due.fmt()})"
                }
            }
            for (p in payments) {
                val e = entryById[p.entryId]
                if (e == null || e.status != EntryStatus.VALIDATED) {
                    problems += "${p.number} : écriture comptable absente"
                    continue
                }
                val d = linesByEntry[e.id].orEmpty().sumBd { it.debit }
                if (!d.sameAs(p.amount)) problems += "${p.number} : montant de l'écriture (${d.fmt()}) différent du règlement (${p.amount.fmt()})"
            }
            out += result("Cohérence documents / écritures", problems, Severity.ERROR, "Chaque facture et règlement correspond à son écriture.", "Documents incohérents")
        }

        // 7. Règlements et imputations
        run {
            val problems = mutableListOf<String>()
            val invoiceById = invoices.associateBy { it.id }
            val paymentById = payments.associateBy { it.id }
            for ((invoiceId, list) in allocations.groupBy { it.invoiceId }) {
                val inv = invoiceById[invoiceId]
                if (inv == null) { problems += "Imputation sur une facture inexistante"; continue }
                if (list.sumBd { it.amount }.compareTo(inv.amountDue()) > 0) problems += "${inv.number ?: "?"} : imputations supérieures au montant dû"
            }
            for ((sourceId, list) in allocations.groupBy { it.sourceId }) {
                val total = list.sumBd { it.amount }
                val p = paymentById[sourceId]
                val cn = invoiceById[sourceId]
                when {
                    p != null && p.cancelled -> problems += "${p.number} : règlement annulé mais encore imputé"
                    p != null && total.compareTo(p.amount) > 0 -> problems += "${p.number} : imputations supérieures au montant réglé"
                    p == null && cn != null && total.compareTo(cn.totalTtc) > 0 -> problems += "${cn.number ?: "?"} : imputations supérieures à l'avoir"
                    p == null && cn == null -> problems += "Imputation sans règlement ni avoir d'origine"
                }
            }
            out += result("Règlements et imputations", problems, Severity.ERROR, "Imputations cohérentes avec les règlements, avoirs et factures.", "Imputations anormales")
        }

        // 8. Rapprochement clients / fournisseurs
        run {
            val customersId = mappings[MappingKey.CUSTOMERS.name]
            val suppliersId = mappings[MappingKey.SUPPLIERS.name]
            val problems = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val info = mutableListOf<String>()
            // Compte du tiers = compte collectif + compte d'avances (411 + 419, 401 + 409)
            fun check(accountIds: Set<String>, label: String, customers: Boolean) {
                if (accountIds.isEmpty()) return
                val ls = lines.filter { it.accountId in accountIds }
                val gl = ls.sumBd { it.debit }.subtract(ls.sumBd { it.credit })
                val orphan = ls.filter { it.thirdPartyId == null }
                if (orphan.isNotEmpty()) problems += "$label : ${orphan.size} ligne(s) sans tiers sur le compte collectif ou d'avances"
                val valid = invoices.filter { it.status == DocStatus.VALIDATED }
                val docs = if (customers) {
                    valid.filter { it.kind == InvoiceKind.SALE }.sumBd { it.amountDue() }
                        .subtract(valid.filter { it.kind == InvoiceKind.SALE_CREDIT }.sumBd { it.totalTtc })
                        .subtract(payments.filter { !it.cancelled && it.direction == PaymentDirection.RECEIPT }.sumBd { it.amount })
                } else {
                    valid.filter { it.kind == InvoiceKind.PURCHASE }.sumBd { it.amountDue() }
                        .subtract(valid.filter { it.kind == InvoiceKind.PURCHASE_CREDIT }.sumBd { it.totalTtc })
                        .subtract(payments.filter { !it.cancelled && it.direction == PaymentDirection.DISBURSEMENT }.sumBd { it.amount })
                }
                // Situation initiale, frais et ristournes : mouvements connus qui ne sont ni factures ni règlements
                val known = ls.filter { l ->
                    val e = entryById[l.entryId]
                    val origin = e?.reversalOfId?.let { entryById[it] } ?: e
                    origin?.sourceType == EntrySource.OPENING || origin?.sourceType == EntrySource.PARTY_OPERATION
                }
                val knownNet = known.sumBd { it.debit }.subtract(known.sumBd { it.credit })
                val glSigned = (if (customers) gl else gl.negate())
                    .subtract(if (customers) knownNet else knownNet.negate())
                if (!glSigned.sameAs(docs)) {
                    warnings += "$label : solde comptable ${glSigned.fmt()} Ar ≠ solde des documents ${docs.fmt()} Ar (écriture manuelle sur le compte collectif ou anomalie)"
                }
                // Soldes de sens inverse par tiers (avances, avoirs non imputés)
                for ((tpId, tls) in ls.filter { it.thirdPartyId != null }.groupBy { it.thirdPartyId!! }) {
                    val net = tls.sumBd { it.debit }.subtract(tls.sumBd { it.credit })
                    val signed = if (customers) net else net.negate()
                    if (signed.signum() < 0) info += "$label — ${parties[tpId]?.name ?: "?"} : solde inverse de ${signed.negate().fmt()} Ar (avance ou avoir à imputer)"
                }
            }
            check(setOfNotNull(customersId, mappings[MappingKey.CUSTOMER_ADVANCES.name]), "Clients", true)
            check(setOfNotNull(suppliersId, mappings[MappingKey.SUPPLIER_ADVANCES.name]), "Fournisseurs", false)
            out += when {
                problems.isNotEmpty() -> Control("Rapprochement clients / fournisseurs", Severity.ERROR, "Lignes sans tiers (${problems.size})", cap(problems + warnings))
                warnings.isNotEmpty() -> Control("Rapprochement clients / fournisseurs", Severity.WARN, "Écarts avec les documents (${warnings.size})", cap(warnings))
                info.isNotEmpty() -> Control("Rapprochement clients / fournisseurs", Severity.INFO, "Soldes inverses à régulariser (${info.size})", cap(info))
                else -> Control("Rapprochement clients / fournisseurs", Severity.OK, "Comptes collectifs égaux aux soldes des documents.")
            }
        }

        // 9. Stock
        run {
            val problems = mutableListOf<String>()
            val byProduct = movements.groupBy { it.productId }
            for (p in products.filter { it.isStocked }) {
                val ms = byProduct[p.id].orEmpty()
                val q = ms.sumBd { it.qty }
                val v = ms.sumBd { it.value }
                if (!q.sameAs(p.qtyOnHand)) problems += "${p.name} : quantité ${p.qtyOnHand.fmtQty()} ≠ somme des mouvements ${q.fmtQty()}"
                if (!v.sameAs(p.stockValue)) problems += "${p.name} : valeur ${p.stockValue.fmt()} ≠ somme des mouvements ${v.fmt()}"
                if (p.qtyOnHand.signum() < 0) problems += "${p.name} : stock négatif (${p.qtyOnHand.fmtQty()})"
            }
            val stockId = mappings[MappingKey.STOCK.name]
            if (stockId != null) {
                val ls = lines.filter { it.accountId == stockId }
                val gl = ls.sumBd { it.debit }.subtract(ls.sumBd { it.credit })
                val inv = products.sumBd { it.stockValue }
                if (!gl.sameAs(inv)) problems += "Valeur du stock ${inv.fmt()} Ar ≠ compte de stock ${gl.fmt()} Ar (écriture manuelle sur le compte de stock ?)"
            }
            out += result("Stock", problems, Severity.ERROR, "Quantités, valeurs et comptabilité du stock concordent.", "Anomalies de stock")
        }

        // 10. Trésorerie
        run {
            val problems = mutableListOf<String>()
            val warns = mutableListOf<String>()
            fun net(key: MappingKey): BigDecimal {
                val id = mappings[key.name] ?: return BigDecimal.ZERO
                val ls = lines.filter { it.accountId == id }
                return ls.sumBd { it.debit }.subtract(ls.sumBd { it.credit })
            }
            if (net(MappingKey.CASH).signum() < 0) problems += "Caisse créditrice de ${net(MappingKey.CASH).negate().fmt()} Ar"
            if (net(MappingKey.MOBILE).signum() < 0) problems += "Mobile Money créditeur de ${net(MappingKey.MOBILE).negate().fmt()} Ar"
            if (net(MappingKey.BANK).signum() < 0) warns += "Banque à découvert de ${net(MappingKey.BANK).negate().fmt()} Ar"
            out += if (problems.isNotEmpty()) Control("Trésorerie", Severity.ERROR, "Soldes impossibles", cap(problems + warns))
            else if (warns.isNotEmpty()) Control("Trésorerie", Severity.WARN, "Découvert bancaire", warns)
            else Control("Trésorerie", Severity.OK, "Aucun solde de trésorerie anormal.")
        }

        // 11. Échéances dépassées
        run {
            val problems = mutableListOf<String>()
            var customersTotal = BigDecimal.ZERO
            var suppliersTotal = BigDecimal.ZERO
            val allocByInvoice = allocations.groupBy { it.invoiceId }
            for (inv in invoices.filter { it.status == DocStatus.VALIDATED && (it.kind == InvoiceKind.SALE || it.kind == InvoiceKind.PURCHASE) }) {
                val open = inv.amountDue().subtract(allocByInvoice[inv.id].orEmpty().sumBd { it.amount })
                if (open.signum() > 0 && inv.dueDate.isBefore(today)) {
                    if (inv.kind == InvoiceKind.SALE) customersTotal = customersTotal.add(open) else suppliersTotal = suppliersTotal.add(open)
                    val days = ChronoUnit.DAYS.between(inv.dueDate, today)
                    problems += "${inv.number ?: "?"} — ${parties[inv.thirdPartyId]?.name ?: "?"} : ${open.fmt()} Ar en retard de $days j"
                }
            }
            out += if (problems.isEmpty()) Control("Échéances dépassées", Severity.OK, "Aucune facture échue impayée.")
            else Control(
                "Échéances dépassées", Severity.WARN,
                "Clients en retard : ${customersTotal.fmt()} Ar · Fournisseurs à régler : ${suppliersTotal.fmt()} Ar",
                cap(problems)
            )
        }

        // 12. TVA (information)
        run {
            fun net(key: MappingKey): BigDecimal {
                val id = mappings[key.name] ?: return BigDecimal.ZERO
                val ls = lines.filter { it.accountId == id }
                return ls.sumBd { it.debit }.subtract(ls.sumBd { it.credit })
            }
            val due = net(MappingKey.VAT_COLLECTED).negate().subtract(net(MappingKey.VAT_DEDUCTIBLE))
            out += Control(
                "Situation de TVA", Severity.INFO,
                if (due.signum() >= 0) "TVA à décaisser (collectée − déductible) : ${due.fmt()} Ar" else "Crédit de TVA : ${due.negate().fmt()} Ar"
            )
        }

        return out
    }
}
