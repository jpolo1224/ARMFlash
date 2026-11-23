package com.armsx.flashpoint.core

/**
 * Extreme content: games unsuitable for children, hidden everywhere while Config > Show Extreme
 * Games is off (the default).
 *
 * [tags] is Flashpoint's own Extreme list, the one flashpointarchive.org's search hides unless
 * asked (FlashpointProject/Flashpoint-Website-Deno, data/filter.json, December 2025), plus
 * LEGACY-Extreme, the tag the old Extreme checkbox became (flashpoint-database-api's list).
 * Searches leave out games with any of them (a tag's other names count too), in the database.
 *
 * [ids] are games Flashpoint has none of those tags on that ARMFlash hides with them: every
 * search with the tag filter sends them along, and the native core takes them out of the
 * database's tag filter table, so pages, totals and random picks all leave them out.
 */
object Extreme {
    val tags: List<String> = listOf(
        "Adult", "Age Regression", "Ambiguous Penetration", "Anal", "Anal Insertion", "Andromorph",
        "Anilingus", "Asphyxiophilia", "BDSM", "Bestiality", "Bimbofication", "Breast Milking",
        "Cannibalism", "Cartoon Porn", "Cunnilingus", "Enema", "Expansion", "Fellatio", "Fingering",
        "Fisting", "Flatulence", "Footjob", "Frottage", "Futanari", "Gachimuchi", "Gloryhole", "Gore",
        "Group", "Gynomorph", "Handjob", "Hentai", "Homophobia", "Hypnosis", "Incest", "Infantilism",
        "Inflation", "Intersex", "Interspecies", "Kabeshiri", "LEGACY-Extreme", "Macrophilia",
        "Male Futanari", "Masturbation", "Multiple Penises", "Muscle Growth", "Necrophilia", "Obesity",
        "Oral", "Oviposition", "Paizuri", "Podophilia", "Popping", "Porn", "Pregnancy", "Quicksand",
        "Racism", "Scat", "Self Oral", "Selfcest", "Sex Simulator", "Sex Toys", "Sexual Content",
        "Sexual Violence", "Solo", "Spanking", "Stereotyping", "Strong Language", "Strong Violence",
        "Tentacles", "Tickling", "Touching", "Transformation", "Transphobia", "Tribadism", "Undressing",
        "Urination", "Vaginal", "Vaginal Insertion", "Vomit", "Vore", "Weight Gain",
    )

    val ids: Set<String> = setOf(
        "d4295219-e61c-4952-b964-45c388f81e40", // Operation: Pedopriest (Molleindustria)
    )

    private val lower = tags.map { it.lowercase() }.toSet()

    /** [g] carries an Extreme tag (by the name its tags list shows) or is one of [ids]. */
    fun isExtreme(g: Game) = g.id in ids || g.tags.split(';').any { it.trim().lowercase() in lower }
}
