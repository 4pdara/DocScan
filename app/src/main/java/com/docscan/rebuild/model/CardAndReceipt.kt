package com.docscan.rebuild.model

/**
 * The details of a business card in its recognised text (1.3.4): phone numbers (mobile first), e-mail, web site,
 * and the name, title and company from the remaining lines. Rules only, no network.
 */
object BusinessCard {
    data class Contact(val name: String?, val company: String?, val title: String?, val phones: List<String>, val emails: List<String>, val website: String?, val address: String?) {
        val empty get() = name == null && company == null && phones.isEmpty() && emails.isEmpty() && website == null
    }

    private val email = Regex("[A-Za-z0-9._%+\\-]+ ?@ ?[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}")
    private val phone = Regex("(?<![\\d+])(?:\\+ ?\\d{1,3}[ \\-.]?)?\\(?\\d{1,4}\\)?(?:[ \\-.]?\\d{2,4}){2,4}(?!\\d)")
    private val web = Regex("(?i)\\b(?:https?://)?(?:www\\.)[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)+(?:/\\S*)?|\\bhttps?://\\S+")
    private val titles = listOf("מנכ\"ל", "מנכ״ל", "מנהל", "מנהלת", "יועץ", "יועצת", "עו\"ד", "עו״ד", "רו\"ח", "רו״ח", "ד\"ר", "ד״ר", "מהנדס", "מהנדסת", "סמנכ\"ל", "סמנכ״ל", "בעלים",
        "CEO", "CTO", "CFO", "COO", "Manager", "Director", "Engineer", "Consultant", "Founder", "Owner", "President", "Partner", "Designer", "Developer", "Sales", "Attorney", "Lawyer")
    private val companyHints = listOf("בע\"מ", "בע״מ", "חברת", "קבוצת", "סטודיו", "משרד", "Ltd", "LTD", "Inc", "INC", "LLC", "GmbH", "Group", "Studio", "Company", "Co.")
    private val addressHints = listOf("רחוב", "רח'", "שד'", "שדרות", "ת.ד", "מיקוד", "Street", "St.", "Ave", "Road", "Rd.", "Suite", "P.O.")

    fun parse(text: String): Contact {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val emails = lines.flatMap { email.findAll(it).map { m -> m.value.replace(" ", "") } }.distinct()
        val website = lines.firstNotNullOfOrNull { l -> web.find(l)?.value?.takeIf { !it.contains('@') } }
        val phones = lines.flatMap { l ->
            val withoutMail = email.replace(l, " ")
            phone.findAll(withoutMail).map { it.value.trim() }.filter { it.count(Char::isDigit) in 9..15 }.toList()
        }.distinct().sortedBy { p -> val d = p.filter(Char::isDigit); if (d.startsWith("05") || d.startsWith("9725")) 0 else 1 }
        // Lines left once contacts are taken out: the name, the title and the company.
        val rest = lines.filter { l -> email.find(l) == null && web.find(l) == null && phone.findAll(l).none { it.value.count(Char::isDigit) >= 9 } }
            .map { it.trim(' ', '|', '•', '-', ',') }.filter { l -> l.count { it.isLetter() } >= 2 }
        val address = rest.firstOrNull { l -> addressHints.any { l.contains(it) } || Regex("\\d{1,4}\\s*,?\\s*\\p{L}{2,}").containsMatchIn(l) && l.any(Char::isDigit) && l.count(Char::isLetter) > 4 }
        val title = rest.firstOrNull { l -> l != address && titles.any { t -> l.contains(t, ignoreCase = t.first().isLetter() && t.first() in 'A'..'Z') } }
        val company = rest.firstOrNull { l -> l != address && l != title && companyHints.any { l.contains(it) } }
        val name = rest.firstOrNull { l -> l != address && l != title && l != company && l.split(Regex("\\s+")).size in 2..4 && l.none(Char::isDigit) }
        return Contact(name, company ?: rest.firstOrNull { it != name && it != title && it != address && it.none(Char::isDigit) }, title, phones, emails, website, address)
    }
}

/**
 * The main details of a receipt in its recognised text (1.3.4): the shop (its first line of words), the date, and the
 * total (the amount on a "total" line, else the largest amount on the receipt).
 */
object Receipt {
    data class Summary(val shop: String?, val date: String?, val total: String?, val amounts: List<String>)

    private val amount = Regex("(?<![\\d.,])(?:₪\\s?)?(\\d{1,3}(?:,\\d{3})+|\\d+)[.,](\\d{2})(?!\\d)(?:\\s?₪)?")
    private val date = Regex("(?<!\\d)(\\d{1,2})[./\\-](\\d{1,2})[./\\-](\\d{2}|\\d{4})(?!\\d)")
    private val totalWords = listOf("סה\"כ לתשלום", "סה״כ לתשלום", "לתשלום", "סה\"כ", "סה״כ", "סך הכל", "סך הכול", "TOTAL", "Total", "total", "AMOUNT DUE", "Amount due", "Grand total", "GRAND TOTAL")

    private fun value(m: MatchResult) = m.groupValues[1].replace(",", "") + "." + m.groupValues[2]

    fun parse(text: String): Summary {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val shop = lines.firstOrNull { l -> l.count { it.isLetter() } >= 3 && date.find(l) == null }
        val day = lines.firstNotNullOfOrNull { l -> date.find(l)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            if (d in 1..31 && mo in 1..12) m.value else null } }
        val amounts = lines.flatMap { l -> amount.findAll(l).map(::value).toList() }
        // The last "total" line wins (subtotals come first); "לתשלום" lines before plain "סה"כ".
        val total = totalWords.firstNotNullOfOrNull { word -> lines.lastOrNull { it.contains(word) && amount.find(it) != null }?.let { l -> amount.findAll(l).last().let(::value) } }
            ?: amounts.maxByOrNull { it.toDoubleOrNull() ?: 0.0 }
        return Summary(shop, day, total, amounts)
    }
}
