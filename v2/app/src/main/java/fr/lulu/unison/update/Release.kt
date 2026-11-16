package fr.lulu.unison.update

/** Une version publiée sur GitHub. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/** Compare deux numéros de version « 1.2.3 » (un éventuel « v » ou suffixe est ignoré). */
object Versions {
    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** Vrai si [candidate] est strictement plus récente que [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
