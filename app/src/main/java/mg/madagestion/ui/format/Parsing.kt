package mg.madagestion.ui.format

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import mg.madagestion.core.BusinessException

/** Lit un montant saisi (accepte espaces et virgule) ; vide = 0 ; sinon erreur lisible. */
fun parseDecimal(text: String, what: String = "Montant"): BigDecimal {
    val t = text.trim().replace(" ", "").replace(" ", "").replace(',', '.')
    if (t.isEmpty()) return BigDecimal.ZERO
    return t.toBigDecimalOrNull() ?: throw BusinessException("$what invalide : « $text ».")
}

/** Version tolérante pour les totaux affichés en direct pendant la saisie. */
fun lenientDecimal(text: String): BigDecimal =
    text.trim().replace(" ", "").replace(" ", "").replace(',', '.').toBigDecimalOrNull() ?: BigDecimal.ZERO

fun formatTime(ms: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString().replace('T', ' ').take(16)
