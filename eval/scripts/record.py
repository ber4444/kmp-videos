"""Record live fixtures. API calls spend money; use --dry-run to plan."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from recording import record


def main():
    record()


if __name__ == "__main__":
    main()
