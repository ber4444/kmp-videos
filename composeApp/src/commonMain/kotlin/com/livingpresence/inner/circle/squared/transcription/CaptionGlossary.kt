package com.livingpresence.inner.circle.squared.transcription

/**
 * The vocabulary of the events, handed to Soniox as session `context` so the model knows the
 * domain before it hears a word of it.
 *
 * Two problems, two sections of the context object:
 *
 * - **Transcription.** The talks are dense with terms a general model has no reason to
 *   expect — *Influence C*, *the four wordless breaths*, *the cycle of the ninth life*. Left
 *   to guess, the ASR writes the nearest common phrase and the caption reads as nonsense.
 *   [TERMS] goes into `context.terms`, which pins spelling and casing for exactly these.
 * - **Translation.** A term the model transcribes correctly can still be translated wrongly,
 *   because the right rendering is the one this teaching already settled on and not the
 *   literal one: *Uncreated light* is `Несотворённый Свет` in Russian and
 *   `Nemteremtett fény` in Hungarian — the accepted terms, not a fresh translation of two
 *   English words. [translationTermsFor] supplies `context.translation_terms` for the
 *   language captions are actually being written in.
 *
 * The table is one row per term, in the order the accepted-terms list gives them, so it
 * stays diffable against that list. Every language must render every term in [TERMS] and
 * nothing else — `CaptionGlossaryTest` enforces it, so a term or a language added to one map
 * and forgotten in another can't ship. Adding a language is one map plus one line in
 * [TRANSLATIONS]; adding a term means adding it to *every* map.
 *
 * Where the accepted-terms list offers a second acceptable wording, the primary is used and
 * the alternate is kept in a comment: Soniox takes exactly one target per source. Where a
 * term has two English forms that are both said aloud (*Influence C* / *C Influence*), both
 * are listed as sources pointing at the same target.
 *
 * Soniox caps the whole context object at ~8,000 tokens (~10,000 characters) and rejects the
 * session outright above that; this glossary is an order of magnitude under, but the test
 * keeps an eye on it as terms accumulate.
 *
 * These renderings are the community's, not a translation this code invented — correct them
 * against that list rather than by ear. The three deliberate departures are marked with the
 * list's own wording in a comment, so nobody "fixes" them back: Hungarian *higher self /
 * centers / school* read `Felsőbb`, not `Magasabb`, because `felsőbb` is the pair to the
 * `Alsóbb én` the list already uses.
 */
internal object CaptionGlossary {

    /**
     * Terms boosted for transcription, sent as `context.terms` on every session whether or
     * not captions are being translated — getting the English right is the precondition for
     * translating it.
     */
    val TERMS: List<String> = listOf(
        "Uncreated light",
        "Short BE",
        "Long BE",
        "Influence C",
        "C Influence",
        "The sequence",
        "Self-remembering",
        "Third Eye",
        "Lower self",
        "Higher self",
        "Spontaneous reality",
        "Conscious love",
        "Uncreated Love",
        "Transforming friction",
        "Transforming suffering",
        "Supernatural presence",
        "Higher centers",
        "Steward",
        "Steward's work",
        "Higher school",
        "Conscious momentum",
        "Quiet Grace",
        "Conscious courage",
        "External consideration",
        "Second-line work",
        "Third-line work",
        "World 6",
        "World 12",
        "Four wordless breaths",
        "Nine conscious roles",
        "Ninth life",
        "Cycle of the ninth life",
        "Conscious destiny",
        "Second state of unconsciousness",
        "Observing 'I'",
        "Intellectual center",
        "Astral body",
        "Evil eye",
        "Prolonged presence",
        "Will of the Absolute",
        "Effortless prosperity",
        "Ultimate serenity",
        "Freedom from imagination",
        "Self-pity",
        "Inner fitness",
        "Inner sickness",
        "Unconscious territory",
        "Quiet conscious zone",
        "Spontaneous presence",
    )

    /** Accepted Hungarian. */
    private val HUNGARIAN = mapOf(
        "Uncreated light" to "Teremtetlen fény", // list says "Nemteremtett fény"
        "Short BE" to "Rövid BE", // also: rövid «Lenni»
        "Long BE" to "Hosszú BE", // also: hosszú «Lenni»
        "Influence C" to "C-befolyás",
        "C Influence" to "C-befolyás",
        "The sequence" to "A sorozat",
        "Self-remembering" to "Önemlékezés",
        "Third Eye" to "Harmadik szem",
        "Lower self" to "Alsóbb én",
        "Higher self" to "Felsőbb én", // list says "Magasabb én"; felsőbb is the pair to alsóbb
        "Spontaneous reality" to "Spontán valóság",
        "Conscious love" to "Tudatos szeretet",
        "Uncreated Love" to "Teremtetlen szeretet", // list says "Nemteremtett szeretet"; follows the light
        "Transforming friction" to "A súrlódás átalakítása", // also: transzformációja
        "Transforming suffering" to "A szenvedés átalakítása",
        "Supernatural presence" to "Természetfeletti jelenlét",
        "Higher centers" to "Felsőbb központok", // list says "Magasabb központok"
        "Steward" to "Intéző",
        "Steward's work" to "Az intéző munkája",
        "Higher school" to "Felsőbb iskola", // list says "Magasabb iskola"
        "Conscious momentum" to "Tudatos lendület",
        "Quiet Grace" to "Csendes Kegyelem",
        "Conscious courage" to "Tudatos bátorság",
        "External consideration" to "Külső figyelembevétel", // list says "tekintetbevétel"; also: külső igazodás
        "Second-line work" to "Második vonalas munka",
        "Third-line work" to "Harmadik vonalas munka",
        "World 6" to "6-os Világ",
        "World 12" to "12-es Világ",
        "Four wordless breaths" to "Négy szótlan lélegzet",
        "Nine conscious roles" to "Kilenc tudatos szerep",
        "Ninth life" to "Kilencedik élet",
        "Cycle of the ninth life" to "A kilencedik élet ciklusa",
        "Conscious destiny" to "Tudatos rendeltetés", // also: Tudatos sors
        "Second state of unconsciousness" to "Az öntudatlanság második állapota",
        "Observing 'I'" to "Megfigyelő «Én»",
        "Intellectual center" to "Intellektuális központ", // also: értelmi központ
        "Astral body" to "Asztráltest",
        "Evil eye" to "Gonosz szem", // also: rontó szem
        "Prolonged presence" to "Elnyújtott jelenlét", // also: Tartós jelenlét
        "Will of the Absolute" to "Az Abszolút akarata",
        "Effortless prosperity" to "Erőfeszítés nélküli bőség", // also: erőfeszítés nélküli jólét
        "Ultimate serenity" to "Végső derű", // also: Végső megnyugvás
        "Freedom from imagination" to "Mentesség a képzelődéstől", // also: Szabadság a képzelődéstől
        "Self-pity" to "Önsajnálat",
        "Inner fitness" to "Belső erőnlét", // the list's own alternate; its primary "Belső fittség" is an anglicism
        "Inner sickness" to "Belső betegség",
        "Unconscious territory" to "Öntudatlan terület", // also: tudattalan mező
        "Quiet conscious zone" to "Csendes tudatos zóna",
        "Spontaneous presence" to "Spontán jelenlét",
    )

    /** The original Russian, which for most of this vocabulary is the source rather than a translation. */
    private val RUSSIAN = mapOf(
        "Uncreated light" to "Несотворённый Свет",
        "Short BE" to "Краткое BE", // also: краткое «Быть»
        "Long BE" to "Долгое BE", // also: долгое «Быть»
        "Influence C" to "Влияние C",
        "C Influence" to "Влияние C",
        "The sequence" to "Последовательность",
        "Self-remembering" to "Самовоспоминание",
        "Third Eye" to "Третий глаз",
        "Lower self" to "Низшая суть", // also: Низшее «я»
        "Higher self" to "Высшая суть",
        "Spontaneous reality" to "Спонтанная реальность",
        "Conscious love" to "Сознательная любовь",
        "Uncreated Love" to "Несотворённая любовь",
        "Transforming friction" to "Преобразование трения", // also: трансформация трения
        "Transforming suffering" to "Преобразование страдания",
        "Supernatural presence" to "Сверхъестественное присутствие",
        "Higher centers" to "Высшие центры",
        "Steward" to "Управляющий",
        "Steward's work" to "Работа управляющего",
        "Higher school" to "Высшая школа",
        "Conscious momentum" to "Сознательный импульс",
        "Quiet Grace" to "Тихая Благодать",
        "Conscious courage" to "Сознательная смелость",
        "External consideration" to "Внешнее учитывание",
        "Second-line work" to "Вторая линия работы",
        "Third-line work" to "Третья линия работы",
        "World 6" to "Мир 6",
        "World 12" to "Мир 12",
        "Four wordless breaths" to "Четыре бессловесных дыхания",
        "Nine conscious roles" to "Девять сознательных ролей",
        "Ninth life" to "Девятая жизнь",
        "Cycle of the ninth life" to "Цикл девятой жизни",
        "Conscious destiny" to "Сознательное предназначение",
        "Second state of unconsciousness" to "Второе состояние несознательности",
        "Observing 'I'" to "Наблюдающее «Я»",
        "Intellectual center" to "Интеллектуальный центр",
        "Astral body" to "Астральное тело",
        "Evil eye" to "Дурной глаз",
        "Prolonged presence" to "Продлённое присутствие",
        "Will of the Absolute" to "Воля Абсолюта",
        "Effortless prosperity" to "Изобилие, достигаемое без усилий",
        "Ultimate serenity" to "Высшее умиротворение",
        "Freedom from imagination" to "Свобода от воображения",
        "Self-pity" to "Жалость к себе",
        "Inner fitness" to "Внутренняя форма",
        "Inner sickness" to "Внутренняя болезнь",
        "Unconscious territory" to "Область несознательного",
        "Quiet conscious zone" to "Зона тишины и осознанности",
        "Spontaneous presence" to "Спонтанное присутствие",
    )

    /** Source term → accepted target, per Soniox language code. More languages go here. */
    private val TRANSLATIONS: Map<String, Map<String, String>> = mapOf(
        "hu" to HUNGARIAN,
        "ru" to RUSSIAN,
    )

    /** The languages a glossary exists for — a subset of [CaptionLanguage.SUPPORTED]. */
    val LANGUAGES: Set<String> get() = TRANSLATIONS.keys

    /**
     * The accepted translations for [target], or empty when captions aren't translated or
     * the device's language has no glossary yet. Empty is the ordinary case for most of
     * [CaptionLanguage.SUPPORTED]: those sessions still get [TERMS], and Soniox translates
     * the vocabulary on its own.
     */
    fun translationTermsFor(target: String?): Map<String, String> =
        target?.let { TRANSLATIONS[it] }.orEmpty()
}
