package mg.madagestion.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Money {
    val ZERO: BigDecimal = BigDecimal.ZERO

    /** Tous les montants sont arrondis à l'ariary entier. */
    fun ar(v: BigDecimal): BigDecimal = v.setScale(0, RoundingMode.HALF_UP)

    /** Les quantités gardent 3 décimales. */
    fun qty(v: BigDecimal): BigDecimal = v.setScale(3, RoundingMode.HALF_UP)
}

/** Montant lisible : « 1 250 000 » (séparateur de milliers = espace). */
fun BigDecimal.fmt(): String {
    val symbols = DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = ' ' }
    return DecimalFormat("#,##0", symbols).format(Money.ar(this))
}

/** Quantité lisible, sans zéros inutiles. */
fun BigDecimal.fmtQty(): String {
    val symbols = DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = ' '; decimalSeparator = ',' }
    return DecimalFormat("#,##0.###", symbols).format(this)
}

/** Égalité numérique (ignore l'échelle : 10 et 10.00 sont égaux). */
infix fun BigDecimal.sameAs(other: BigDecimal): Boolean = this.compareTo(other) == 0

fun BigDecimal.isPositive(): Boolean = this.signum() > 0

inline fun <T> Iterable<T>.sumBd(selector: (T) -> BigDecimal): BigDecimal {
    var total = BigDecimal.ZERO
    for (item in this) total = total.add(selector(item))
    return total
}
