package mg.madagestion.domain.accounting

import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.Money
import mg.madagestion.core.newId
import mg.madagestion.core.sameAs
import mg.madagestion.core.sumBd
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.Account
import mg.madagestion.data.model.AccountMapping
import mg.madagestion.data.model.AuditLog
import mg.madagestion.data.model.EntryLine
import mg.madagestion.data.model.EntryStatus
import mg.madagestion.data.model.Journal
import mg.madagestion.data.model.JournalEntry
import mg.madagestion.data.model.JournalType
import mg.madagestion.data.model.Period
import mg.madagestion.data.model.Sequence
import mg.madagestion.data.model.ThirdPartyType

/** Ligne d'écriture telle que saisie ou générée (avant enregistrement). */
data class LineInput(
    val accountId: String,
    val thirdPartyId: String? = null,
    val label: String = "",
    val debit: BigDecimal = BigDecimal.ZERO,
    val credit: BigDecimal = BigDecimal.ZERO
)

/**
 * Moteur comptable : seul point d'entrée pour créer, valider ou contrepasser une écriture.
 * Règles appliquées ici (et non dans l'interface) :
 *  - écriture équilibrée (débit = crédit), au moins deux lignes, montants positifs ;
 *  - période comptable ouverte ;
 *  - numérotation sans trou attribuée à la validation ;
 *  - écriture validée = verrouillée ; correction uniquement par contrepassation ;
 *  - comptes collectifs clients/fournisseurs : tiers obligatoire ;
 *  - tout est tracé dans le journal d'audit.
 */
class Accounting(private val db: AppDatabase) {

    // ------------------------------------------------------------------
    // Outils transverses
    // ------------------------------------------------------------------

    suspend fun audit(companyId: String, action: String, entityType: String, entityId: String, detail: String = "") {
        db.auditDao().insert(
            AuditLog(newId(), companyId, System.currentTimeMillis(), action, entityType, entityId, detail)
        )
    }

    /** Séquence sans trou : à appeler dans la même transaction que la validation. */
    suspend fun nextNumber(companyId: String, name: String): Int {
        val next = (db.sequenceDao().get(companyId, name)?.last ?: 0) + 1
        db.sequenceDao().upsert(Sequence(companyId, name, next))
        return next
    }

    suspend fun mappingIdOrNull(companyId: String, key: MappingKey): String? =
        db.mappingDao().get(companyId, key.name)?.accountId

    suspend fun mappingId(companyId: String, key: MappingKey): String =
        mappingIdOrNull(companyId, key)
            ?: throw BusinessException("Le compte « ${key.label} » n'est pas configuré (Paramètres > Correspondance des comptes).")

    suspend fun journalOfType(companyId: String, type: JournalType): Journal =
        db.journalDao().firstOfType(companyId, type)
            ?: throw BusinessException("Aucun journal de type $type n'existe.")

    // ------------------------------------------------------------------
    // Périodes
    // ------------------------------------------------------------------

    suspend fun ensureYear(companyId: String, year: Int) {
        val current = LocalDate.now().year
        if (year < current - 10 || year > current + 2) {
            throw BusinessException("Exercice $year hors des limites autorisées.")
        }
        for (m in 1..12) {
            val start = LocalDate.of(year, m, 1)
            if (db.periodDao().forDate(companyId, start) == null) {
                db.periodDao().insert(
                    Period(
                        id = newId(),
                        companyId = companyId,
                        label = "$year-${m.toString().padStart(2, '0')}",
                        startDate = start,
                        endDate = start.withDayOfMonth(start.lengthOfMonth())
                    )
                )
            }
        }
    }

    suspend fun requireOpenPeriod(companyId: String, date: LocalDate) {
        var p = db.periodDao().forDate(companyId, date)
        if (p == null) {
            ensureYear(companyId, date.year)
            p = db.periodDao().forDate(companyId, date)
        }
        if (p == null) throw BusinessException("Aucune période comptable pour le $date.")
        if (p.closed) throw BusinessException("La période ${p.label} est clôturée : aucune écriture n'est possible à cette date.")
    }

    suspend fun setPeriodClosed(periodId: String, closed: Boolean) {
        db.withTransaction {
            val p = db.periodDao().get(periodId) ?: throw BusinessException("Période introuvable.")
            if (closed) {
                val drafts = db.entryDao().draftCountBetween(p.companyId, p.startDate, p.endDate)
                if (drafts > 0) {
                    throw BusinessException("Clôture impossible : $drafts brouillon(s) dans la période ${p.label}. Validez-les ou supprimez-les.")
                }
            }
            db.periodDao().update(p.copy(closed = closed))
            audit(p.companyId, if (closed) "PERIOD_CLOSE" else "PERIOD_REOPEN", "PERIOD", p.id, p.label)
        }
    }

    // ------------------------------------------------------------------
    // Contrôles de saisie
    // ------------------------------------------------------------------

    private fun normalize(lines: List<LineInput>): List<LineInput> =
        lines.map { it.copy(debit = Money.ar(it.debit), credit = Money.ar(it.credit)) }

    /**
     * @param post true = contrôle complet (validation) ; false = contrôle minimal d'un brouillon.
     */
    private suspend fun checkLines(companyId: String, lines: List<LineInput>, post: Boolean) {
        if (post && lines.size < 2) throw BusinessException("Une écriture comporte au moins deux lignes.")
        // Comptes qui exigent un tiers : collectifs et avances (419 côté clients, 409 côté fournisseurs)
        val partyAccounts: Map<String, ThirdPartyType> = if (!post) emptyMap() else buildMap {
            mappingIdOrNull(companyId, MappingKey.CUSTOMERS)?.let { put(it, ThirdPartyType.CUSTOMER) }
            mappingIdOrNull(companyId, MappingKey.CUSTOMER_ADVANCES)?.let { put(it, ThirdPartyType.CUSTOMER) }
            mappingIdOrNull(companyId, MappingKey.SUPPLIERS)?.let { put(it, ThirdPartyType.SUPPLIER) }
            mappingIdOrNull(companyId, MappingKey.SUPPLIER_ADVANCES)?.let { put(it, ThirdPartyType.SUPPLIER) }
        }
        var debit = BigDecimal.ZERO
        var credit = BigDecimal.ZERO
        lines.forEachIndexed { index, l ->
            val n = index + 1
            if (l.debit.signum() < 0 || l.credit.signum() < 0) {
                throw BusinessException("Ligne $n : un montant négatif est interdit.")
            }
            if (l.debit.signum() > 0 && l.credit.signum() > 0) {
                throw BusinessException("Ligne $n : une ligne ne peut pas être à la fois au débit et au crédit.")
            }
            if (post && l.debit.signum() == 0 && l.credit.signum() == 0) {
                throw BusinessException("Ligne $n : montant nul.")
            }
            val account = db.accountDao().get(l.accountId)
            if (account == null || account.companyId != companyId) throw BusinessException("Ligne $n : compte inconnu.")
            if (post && !account.active) throw BusinessException("Ligne $n : le compte ${account.code} est désactivé.")
            val expected = partyAccounts[account.id]
            if (expected != null) {
                val tp = l.thirdPartyId?.let { db.thirdPartyDao().get(it) }
                    ?: throw BusinessException("Ligne $n : le compte ${account.code} exige un tiers (client ou fournisseur).")
                if (tp.type != expected) {
                    throw BusinessException("Ligne $n : le tiers « ${tp.name} » ne correspond pas au compte ${account.code}.")
                }
            }
            debit = debit.add(l.debit)
            credit = credit.add(l.credit)
        }
        if (post) {
            if (!debit.sameAs(credit)) {
                throw BusinessException(
                    "Écriture déséquilibrée : débit ${debit.toPlainString()} ≠ crédit ${credit.toPlainString()}."
                )
            }
            if (debit.signum() == 0) throw BusinessException("Écriture sans montant.")
        }
    }

    private fun toEntryLines(entryId: String, companyId: String, lines: List<LineInput>): List<EntryLine> =
        lines.mapIndexed { i, l ->
            EntryLine(
                id = newId(),
                entryId = entryId,
                companyId = companyId,
                accountId = l.accountId,
                thirdPartyId = l.thirdPartyId,
                label = l.label.trim(),
                debit = l.debit,
                credit = l.credit,
                position = i
            )
        }

    private suspend fun journalNumber(companyId: String, journal: Journal, date: LocalDate): String {
        val seq = nextNumber(companyId, "ENTRY:${journal.code}:${date.year}")
        return "${journal.code}-${date.year}-${seq.toString().padStart(6, '0')}"
    }

    // ------------------------------------------------------------------
    // Cycle de vie d'une écriture
    // ------------------------------------------------------------------

    /** Crée ou met à jour un brouillon (modifiable tant qu'il n'est pas validé). */
    suspend fun saveDraft(
        companyId: String,
        entryId: String?,
        journalId: String,
        date: LocalDate,
        reference: String,
        label: String,
        lines: List<LineInput>
    ): String = db.withTransaction {
        db.journalDao().get(journalId) ?: throw BusinessException("Journal inconnu.")
        requireOpenPeriod(companyId, date)
        val clean = normalize(lines)
        checkLines(companyId, clean, post = false)
        val existing = entryId?.let { db.entryDao().get(it) }
        if (existing != null && existing.status != EntryStatus.DRAFT) {
            throw BusinessException("Une écriture validée n'est pas modifiable : utilisez la contrepassation.")
        }
        val id = existing?.id ?: newId()
        val base = existing ?: JournalEntry(id = id, companyId = companyId, journalId = journalId, date = date)
        val entry = base.copy(journalId = journalId, date = date, reference = reference.trim(), label = label.trim())
        if (existing == null) {
            db.entryDao().insert(entry)
        } else {
            db.entryDao().update(entry)
            db.entryDao().deleteLines(id)
        }
        db.entryDao().insertLines(toEntryLines(id, companyId, clean))
        audit(companyId, if (existing == null) "ENTRY_CREATE" else "ENTRY_UPDATE", "ENTRY", id)
        id
    }

    suspend fun deleteDraft(entryId: String) {
        db.withTransaction {
            val e = db.entryDao().get(entryId) ?: throw BusinessException("Écriture introuvable.")
            if (e.status != EntryStatus.DRAFT) throw BusinessException("Seul un brouillon peut être supprimé.")
            db.entryDao().delete(entryId)
            audit(e.companyId, "ENTRY_DELETE_DRAFT", "ENTRY", entryId)
        }
    }

    /** Valide un brouillon : contrôles complets, numéro définitif, verrouillage. */
    suspend fun validate(entryId: String) {
        db.withTransaction {
            val e = db.entryDao().get(entryId) ?: throw BusinessException("Écriture introuvable.")
            if (e.status != EntryStatus.DRAFT) throw BusinessException("Cette écriture est déjà validée.")
            val lines = db.entryDao().lines(entryId)
                .map { LineInput(it.accountId, it.thirdPartyId, it.label, it.debit, it.credit) }
            checkLines(e.companyId, lines, post = true)
            requireOpenPeriod(e.companyId, e.date)
            val journal = db.journalDao().get(e.journalId) ?: throw BusinessException("Journal inconnu.")
            val number = journalNumber(e.companyId, journal, e.date)
            db.entryDao().update(
                e.copy(status = EntryStatus.VALIDATED, number = number, validatedAt = System.currentTimeMillis())
            )
            audit(e.companyId, "ENTRY_VALIDATE", "ENTRY", entryId, number)
        }
    }

    /** Crée directement une écriture validée (utilisé par les factures, règlements et mouvements de stock). */
    suspend fun post(
        companyId: String,
        journalId: String,
        date: LocalDate,
        reference: String,
        label: String,
        lines: List<LineInput>,
        sourceType: String?,
        sourceId: String?,
        reversalOfId: String? = null
    ): String = db.withTransaction {
        val clean = normalize(lines)
        checkLines(companyId, clean, post = true)
        requireOpenPeriod(companyId, date)
        val journal = db.journalDao().get(journalId) ?: throw BusinessException("Journal inconnu.")
        val id = newId()
        val number = journalNumber(companyId, journal, date)
        val now = System.currentTimeMillis()
        db.entryDao().insert(
            JournalEntry(
                id = id,
                companyId = companyId,
                journalId = journalId,
                date = date,
                reference = reference,
                label = label,
                status = EntryStatus.VALIDATED,
                number = number,
                sourceType = sourceType,
                sourceId = sourceId,
                reversalOfId = reversalOfId,
                createdAt = now,
                validatedAt = now
            )
        )
        db.entryDao().insertLines(toEntryLines(id, companyId, clean))
        audit(companyId, "ENTRY_POST", "ENTRY", id, number)
        id
    }

    /** Contrepassation : écriture inverse validée, l'originale reste intacte et liée. */
    suspend fun reverse(entryId: String, date: LocalDate, allowSource: Boolean = false): String = db.withTransaction {
        val e = db.entryDao().get(entryId) ?: throw BusinessException("Écriture introuvable.")
        if (e.status != EntryStatus.VALIDATED) throw BusinessException("Seule une écriture validée peut être contrepassée.")
        if (e.reversedById != null) throw BusinessException("Cette écriture est déjà contrepassée.")
        if (e.reversalOfId != null) throw BusinessException("Une contrepassation ne peut pas être contrepassée.")
        if (e.sourceType != null && !allowSource) {
            throw BusinessException("Cette écriture provient d'un document : corrigez le document (avoir, annulation du règlement…).")
        }
        val swapped = db.entryDao().lines(entryId)
            .map { LineInput(it.accountId, it.thirdPartyId, it.label, it.credit, it.debit) }
        val newEntryId = post(
            companyId = e.companyId,
            journalId = e.journalId,
            date = date,
            reference = e.number ?: e.reference,
            label = "Contrepassation de ${e.number ?: ""} ${e.label}".trim(),
            lines = swapped,
            sourceType = null,
            sourceId = null,
            reversalOfId = e.id
        )
        db.entryDao().update(e.copy(reversedById = newEntryId))
        audit(e.companyId, "ENTRY_REVERSE", "ENTRY", e.id, newEntryId)
        newEntryId
    }

    // ------------------------------------------------------------------
    // Plan comptable et correspondances
    // ------------------------------------------------------------------

    suspend fun addAccount(companyId: String, code: String, label: String) {
        val c = code.trim()
        if (!Regex("^[0-9]{2,10}$").matches(c)) throw BusinessException("Le code doit comporter 2 à 10 chiffres.")
        if (label.isBlank()) throw BusinessException("Le libellé est obligatoire.")
        if (db.accountDao().byCode(companyId, c) != null) throw BusinessException("Le compte $c existe déjà.")
        val id = newId()
        db.accountDao().insert(Account(id, companyId, c, label.trim()))
        audit(companyId, "ACCOUNT_CREATE", "ACCOUNT", id, c)
    }

    suspend fun updateAccount(accountId: String, label: String, active: Boolean) {
        db.withTransaction {
            val a = db.accountDao().get(accountId) ?: throw BusinessException("Compte introuvable.")
            if (label.isBlank()) throw BusinessException("Le libellé est obligatoire.")
            if (!active) {
                val used = db.mappingDao().list(a.companyId).any { it.accountId == accountId }
                if (used) throw BusinessException("Ce compte est utilisé dans la correspondance des comptes : changez-la avant de le désactiver.")
            }
            db.accountDao().update(a.copy(label = label.trim(), active = active))
            audit(a.companyId, "ACCOUNT_UPDATE", "ACCOUNT", accountId, a.code)
        }
    }

    suspend fun setMapping(companyId: String, key: MappingKey, accountCode: String) {
        val a = db.accountDao().byCode(companyId, accountCode.trim())
            ?: throw BusinessException("Le compte « ${accountCode.trim()} » n'existe pas dans le plan comptable.")
        if (!a.active) throw BusinessException("Le compte ${a.code} est désactivé.")
        db.mappingDao().upsert(AccountMapping(companyId, key.name, a.id))
        audit(companyId, "MAPPING_SET", "MAPPING", key.name, a.code)
    }

    /** Solde débiteur (positif) ou créditeur (négatif) d'un compte, écritures validées uniquement. */
    suspend fun accountNet(companyId: String, accountId: String): BigDecimal {
        val lines = db.entryDao().validatedLinesForAccount(companyId, accountId)
        return lines.sumBd { it.debit }.subtract(lines.sumBd { it.credit })
    }
}
