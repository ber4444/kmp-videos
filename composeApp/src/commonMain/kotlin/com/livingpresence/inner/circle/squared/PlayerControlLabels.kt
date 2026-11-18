package com.livingpresence.inner.circle.squared

/**
 * "Close", "Play" and "Pause", localized while captions are on.
 *
 * These sit on the same screen as the caption track, and a viewer who picked a
 * translation did so because English is not what they read comfortably — so once
 * a caption language is active, the rest of the top-level controls read in that
 * language too, instead of switching mid-screen. [translateTo] is the Soniox code
 * of whatever caption row is selected (`CaptionMenuOption.translateTo`): it is
 * null for both "captions off" and the untranslated spoken-language row, and both
 * of those want English, so one null check is also the correct answer here, not
 * just a convenient default.
 *
 * Same caveat as `captionOffButtonLabel` in `transcription/CaptionLabels.kt`: the
 * entries marked `// review` are this model's own best-effort translation, not a
 * native speaker's, and should get a second look before shipping.
 */
internal fun closeButtonLabel(translateTo: String?): String =
    translateTo?.let { CLOSE_LABEL[it] } ?: "Close"

/** "Play" or "Pause" for the toggle button, in [translateTo]'s language. */
internal fun playPauseButtonLabel(isPlaying: Boolean, translateTo: String?): String =
    if (isPlaying) {
        translateTo?.let { PAUSE_LABEL[it] } ?: "Pause"
    } else {
        translateTo?.let { PLAY_LABEL[it] } ?: "Play"
    }

private val CLOSE_LABEL: Map<String, String> = mapOf(
    "af" to "Maak toe", // review
    "sq" to "Mbyll", // review
    "ar" to "إغلاق",
    "az" to "Bağla", // review
    "eu" to "Itxi", // review
    "be" to "Закрыць", // review
    "bn" to "বন্ধ করুন", // review
    "bs" to "Zatvori",
    "bg" to "Затвори",
    "ca" to "Tanca",
    "zh" to "关闭",
    "hr" to "Zatvori",
    "cs" to "Zavřít",
    "da" to "Luk",
    "nl" to "Sluiten",
    "en" to "Close",
    "et" to "Sulge",
    "fi" to "Sulje",
    "fr" to "Fermer",
    "gl" to "Pechar",
    "de" to "Schließen",
    "el" to "Κλείσιμο",
    "gu" to "બંધ કરો", // review
    "he" to "סגור",
    "hi" to "बंद करें",
    "hu" to "Bezárás",
    "id" to "Tutup",
    "it" to "Chiudi",
    "ja" to "閉じる",
    "kn" to "ಮುಚ್ಚು", // review
    "kk" to "Жабу", // review
    "ko" to "닫기",
    "lv" to "Aizvērt",
    "lt" to "Uždaryti",
    "mk" to "Затвори", // review
    "ms" to "Tutup", // review
    "ml" to "അടയ്ക്കുക", // review
    "mr" to "बंद करा", // review
    "no" to "Lukk",
    "fa" to "بستن", // review
    "pl" to "Zamknij",
    "pt" to "Fechar",
    "pa" to "ਬੰਦ ਕਰੋ", // review
    "ro" to "Închide",
    "ru" to "Закрыть",
    "sr" to "Затвори", // review
    "sk" to "Zavrieť",
    "sl" to "Zapri",
    "es" to "Cerrar",
    "sw" to "Funga", // review
    "sv" to "Stäng",
    "tl" to "Isara", // review
    "ta" to "மூடு", // review
    "te" to "మూసివేయి", // review
    "th" to "ปิด",
    "tr" to "Kapat",
    "uk" to "Закрити",
    "ur" to "بند کریں", // review
    "vi" to "Đóng",
    "cy" to "Cau", // review
)

private val PLAY_LABEL: Map<String, String> = mapOf(
    "af" to "Speel", // review
    "sq" to "Luaj", // review
    "ar" to "تشغيل",
    "az" to "Oynat", // review
    "eu" to "Erreproduzitu", // review
    "be" to "Прайграць", // review
    "bn" to "চালান", // review
    "bs" to "Reprodukuj",
    "bg" to "Пусни",
    "ca" to "Reprodueix",
    "zh" to "播放",
    "hr" to "Reproduciraj",
    "cs" to "Přehrát",
    "da" to "Afspil",
    "nl" to "Afspelen",
    "en" to "Play",
    "et" to "Esita",
    "fi" to "Toista",
    "fr" to "Lecture",
    "gl" to "Reproducir",
    "de" to "Wiedergabe",
    "el" to "Αναπαραγωγή",
    "gu" to "ચલાવો", // review
    "he" to "הפעל",
    "hi" to "चलाएं",
    "hu" to "Lejátszás",
    "id" to "Putar",
    "it" to "Riproduci",
    "ja" to "再生",
    "kn" to "ಪ್ಲೇ ಮಾಡಿ", // review
    "kk" to "Ойнату", // review
    "ko" to "재생",
    "lv" to "Atskaņot",
    "lt" to "Leisti",
    "mk" to "Пушти", // review
    "ms" to "Main", // review
    "ml" to "പ്ലേ ചെയ്യുക", // review
    "mr" to "प्ले करा", // review
    "no" to "Spill av",
    "fa" to "پخش", // review
    "pl" to "Odtwórz",
    "pt" to "Reproduzir",
    "pa" to "ਚਲਾਓ", // review
    "ro" to "Redare",
    "ru" to "Воспроизвести",
    "sr" to "Пусти", // review
    "sk" to "Prehrať",
    "sl" to "Predvajaj",
    "es" to "Reproducir",
    "sw" to "Cheza", // review
    "sv" to "Spela",
    "tl" to "I-play", // review
    "ta" to "இயக்கு", // review
    "te" to "ప్లే చేయి", // review
    "th" to "เล่น",
    "tr" to "Oynat",
    "uk" to "Відтворити",
    "ur" to "چلائیں", // review
    "vi" to "Phát",
    "cy" to "Chwarae", // review
)

private val PAUSE_LABEL: Map<String, String> = mapOf(
    "af" to "Pouseer", // review
    "sq" to "Pauzë", // review
    "ar" to "إيقاف مؤقت",
    "az" to "Dayandır", // review
    "eu" to "Pausatu", // review
    "be" to "Прыпыніць", // review
    "bn" to "বিরতি", // review
    "bs" to "Pauza",
    "bg" to "Пауза",
    "ca" to "Pausa",
    "zh" to "暂停",
    "hr" to "Pauza",
    "cs" to "Pozastavit",
    "da" to "Pause",
    "nl" to "Pauzeren",
    "en" to "Pause",
    "et" to "Peata",
    "fi" to "Keskeytä",
    "fr" to "Pause",
    "gl" to "Pausa",
    "de" to "Pause",
    "el" to "Παύση",
    "gu" to "થોભો", // review
    "he" to "הפסקה",
    "hi" to "रोकें",
    "hu" to "Szünet",
    "id" to "Jeda",
    "it" to "Pausa",
    "ja" to "一時停止",
    "kn" to "ವಿರಾಮ", // review
    "kk" to "Кідірту", // review
    "ko" to "일시정지",
    "lv" to "Pauzēt",
    "lt" to "Pristabdyti",
    "mk" to "Пауза", // review
    "ms" to "Jeda", // review
    "ml" to "താൽക്കാലികമായി നിർത്തുക", // review
    "mr" to "विराम द्या", // review
    "no" to "Pause",
    "fa" to "مکث", // review
    "pl" to "Wstrzymaj",
    "pt" to "Pausar",
    "pa" to "ਰੋਕੋ", // review
    "ro" to "Pauză",
    "ru" to "Пауза",
    "sr" to "Пауза", // review
    "sk" to "Pozastaviť",
    "sl" to "Premor",
    "es" to "Pausar",
    "sw" to "Sitisha", // review
    "sv" to "Pausa",
    "tl" to "I-pause", // review
    "ta" to "இடைநிறுத்து", // review
    "te" to "పాజ్ చేయి", // review
    "th" to "หยุดชั่วคราว",
    "tr" to "Duraklat",
    "uk" to "Пауза",
    "ur" to "موقوف کریں", // review
    "vi" to "Tạm dừng",
    "cy" to "Oedi", // review
)
