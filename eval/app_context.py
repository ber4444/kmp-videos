"""The session `context` the app sends to Soniox, read out of the app's own source.

The player hands Soniox three things before any audio: a sentence saying what the
recordings are (`context.text`), the boosted vocabulary (`context.terms`), and the accepted
rendering of that vocabulary in the language being captioned (`context.translation_terms`).
All three live in `CaptionGlossary.kt`, in Kotlin, as the single source of truth for the
shipping app.

This module parses them out of that file rather than restating them in Python. A copy would
drift the moment a term is added on one side, and then the eval would be scoring a
configuration nothing ships — which is the one way a translation benchmark can be worse than
no benchmark at all.

The parse is deliberately narrow: `DOMAIN`, the `TERMS` list, and one `mapOf` per language
wired up in `TRANSLATIONS`. Anything it cannot find raises, so a rename in the Kotlin breaks
the eval loudly instead of quietly scoring an empty context.
"""
import os
import re
from typing import Dict, List

GLOSSARY_PATH = os.path.join(
    os.path.dirname(__file__), "..", "composeApp", "src", "commonMain", "kotlin",
    "com", "livingpresence", "inner", "circle", "squared", "transcription", "CaptionGlossary.kt",
)

_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
_PAIR = re.compile(r'"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"')


def _strip_comments(source: str) -> str:
    """Drop `//` comments, respecting string literals.

    Not cosmetic: the glossary's comments quote the alternate wording it did not use
    (`// list says "Nemteremtett fény"`), so a naive string scan would collect renderings
    the app never sends.
    """
    out = []
    for line in source.splitlines():
        in_string = False
        escaped = False
        cut = len(line)
        for i, ch in enumerate(line):
            if escaped:
                escaped = False
                continue
            if ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = not in_string
            elif ch == "/" and not in_string and line[i:i + 2] == "//":
                cut = i
                break
        out.append(line[:cut])
    return "\n".join(out)


def _block(source: str, opener: str) -> str:
    """The text between `opener` and its matching close paren."""
    start = source.index(opener) + len(opener)
    depth = 1
    for i in range(start, len(source)):
        if source[i] == "(":
            depth += 1
        elif source[i] == ")":
            depth -= 1
            if depth == 0:
                return source[start:i]
    raise ValueError(f"unterminated block after {opener!r}")


def _unescape(s: str) -> str:
    return s.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n")


def load_app_context(glossary_path: str = GLOSSARY_PATH) -> Dict[str, object]:
    """Returns {"domain": str, "terms": [str], "translations": {lang: {src: tgt}}}."""
    with open(glossary_path, "r", encoding="utf-8") as f:
        source = _strip_comments(f.read())

    # DOMAIN is a `+`-concatenated multi-line literal; join every piece of it.
    domain_start = source.index("const val DOMAIN")
    domain_end = source.index("val TERMS", domain_start)
    domain = "".join(_unescape(m.group(1)) for m in _STRING.finditer(source[domain_start:domain_end]))
    if not domain:
        raise ValueError("CaptionGlossary.DOMAIN not found")

    terms: List[str] = [
        _unescape(m.group(1))
        for m in _STRING.finditer(_block(source, "val TERMS: List<String> = listOf("))
    ]
    if not terms:
        raise ValueError("CaptionGlossary.TERMS not found")

    # TRANSLATIONS maps a Soniox language code to the name of the map holding that
    # language's renderings ("hu" to HUNGARIAN); follow each one to its own mapOf.
    wiring = _block(source, "private val TRANSLATIONS: Map<String, Map<String, String>> = mapOf(")
    translations: Dict[str, Dict[str, str]] = {}
    for lang, symbol in re.findall(r'"([a-z-]+)"\s+to\s+([A-Z_]+)', wiring):
        block = _block(source, f"private val {symbol} = mapOf(")
        translations[lang] = {_unescape(s): _unescape(t) for s, t in _PAIR.findall(block)}
    if not translations:
        raise ValueError("CaptionGlossary.TRANSLATIONS not found")

    return {"domain": domain, "terms": terms, "translations": translations}


def soniox_context(target_lang: str, glossary_path: str = GLOSSARY_PATH) -> Dict[str, object]:
    """The `context` object the app would send for a session captioning into `target_lang`.

    Mirrors `sonioxContext()` in SonioxClient.kt, including the omissions: a language with
    no glossary gets `text` and `terms` and no `translation_terms` at all, rather than an
    empty array.
    """
    app = load_app_context(glossary_path)
    context: Dict[str, object] = {"text": app["domain"], "terms": app["terms"]}
    pairs = app["translations"].get(target_lang, {})
    if pairs:
        context["translation_terms"] = [{"source": s, "target": t} for s, t in pairs.items()]
    return context
