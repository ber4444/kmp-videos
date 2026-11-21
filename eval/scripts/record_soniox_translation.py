"""Record HU/RU async in-band translation; use --dry-run before billed work."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from soniox_translation import record


def main(argv=None):
    record(argv)


if __name__ == "__main__":
    main()
