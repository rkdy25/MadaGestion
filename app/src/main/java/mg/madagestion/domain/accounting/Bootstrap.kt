package mg.madagestion.domain.accounting

import androidx.room.withTransaction
import java.time.LocalDate
import mg.madagestion.core.newId
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.Account
import mg.madagestion.data.model.AccountMapping
import mg.madagestion.data.model.Company
import mg.madagestion.data.model.Journal

/**
 * Prépare la base au lancement :
 *  - première utilisation : entreprise vide avec plan comptable, journaux, correspondances et exercice courant ;
 *  - mise à jour de l'application : ajoute les rôles comptables apparus depuis (sans rien modifier d'existant).
 */
class Bootstrap(private val db: AppDatabase, private val accounting: Accounting) {

    suspend fun ensure() {
        db.withTransaction {
            val company = db.companyDao().firstOnce()
            if (company == null) create() else completeMappings(company.id)
        }
    }

    private suspend fun create() {
        val companyId = newId()
        db.companyDao().insert(Company(id = companyId, name = "Mon entreprise"))

        val accounts = ChartOfAccounts.pcg.map { (code, label) ->
            Account(id = newId(), companyId = companyId, code = code, label = label)
        }
        db.accountDao().insertAll(accounts)

        val byCode = accounts.associateBy { it.code }
        val mappings = MappingKey.values().mapNotNull { key ->
            byCode[key.defaultCode]?.let { AccountMapping(companyId, key.name, it.id) }
        }
        db.mappingDao().upsertAll(mappings)

        db.journalDao().insertAll(
            ChartOfAccounts.journals.map { (code, label, type) ->
                Journal(id = newId(), companyId = companyId, code = code, label = label, type = type)
            }
        )
        accounting.ensureYear(companyId, LocalDate.now().year)
    }

    /** Rattache chaque rôle comptable encore vide à son compte par défaut, créé au besoin. */
    private suspend fun completeMappings(companyId: String) {
        val mapped = db.mappingDao().list(companyId).map { it.mapKey }.toSet()
        for (key in MappingKey.values()) {
            if (key.name in mapped) continue
            var account = db.accountDao().byCode(companyId, key.defaultCode)
            if (account == null) {
                val label = ChartOfAccounts.pcg.firstOrNull { it.first == key.defaultCode }?.second ?: key.label
                account = Account(id = newId(), companyId = companyId, code = key.defaultCode, label = label)
                db.accountDao().insert(account)
            }
            if (account.active) db.mappingDao().upsert(AccountMapping(companyId, key.name, account.id))
        }
    }
}
