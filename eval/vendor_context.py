"""Validated shipped context for provenance-sensitive refresh recordings."""
import os
from pathlib import Path
from app_context import GLOSSARY_PATH as DEFAULT_GLOSSARY_PATH, load_app_context as read_glossary, soniox_context

GLOSSARY_PATH = Path(os.environ.get("EVAL_GLOSSARY_PATH", DEFAULT_GLOSSARY_PATH))


def load_app_context(target, *, glossary_path=GLOSSARY_PATH):
    if target not in {"hu", "ru"}:
        raise ValueError("Only shipped hu/ru glossary targets are supported")
    app = read_glossary(str(glossary_path))
    terms = app["terms"]
    if len(set(terms)) != len(terms):
        raise ValueError("Duplicate glossary terms")
    for mapping in app["translations"].values():
        if set(mapping) != set(terms) or any(not value for value in mapping.values()):
            raise ValueError("Incomplete glossary translation map")
    return soniox_context(target, str(glossary_path))
