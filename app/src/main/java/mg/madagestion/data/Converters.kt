package mg.madagestion.data

import androidx.room.TypeConverter
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Convertisseurs Room.
 * Les montants sont stockés en TEXTE (BigDecimal) pour rester exacts : jamais de Double/Float.
 * Les dates sont stockées au format ISO (yyyy-MM-dd), triables comme du texte.
 */
class Converters {
    @TypeConverter
    fun fromBigDecimal(v: BigDecimal?): String? = v?.toPlainString()

    @TypeConverter
    fun toBigDecimal(s: String?): BigDecimal? = s?.let { BigDecimal(it) }

    @TypeConverter
    fun fromDate(d: LocalDate?): String? = d?.toString()

    @TypeConverter
    fun toDate(s: String?): LocalDate? = s?.let { LocalDate.parse(it) }
}
