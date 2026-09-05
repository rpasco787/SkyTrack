#!/usr/bin/env python3
"""Print a Markdown summary of Surefire XML reports.

Usage: surefire-summary.py <surefire-reports-dir> [label]
Meant for:  ... >> "$GITHUB_STEP_SUMMARY"
"""
import glob
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    reports_dir = sys.argv[1]
    label = sys.argv[2] if len(sys.argv) > 2 else "Surefire"
    total = failures = errors = skipped = 0
    bad = []
    for path in sorted(glob.glob(f"{reports_dir}/TEST-*.xml")):
        suite = ET.parse(path).getroot()
        t, f, e, s = (int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
        total += t
        failures += f
        errors += e
        skipped += s
        if f or e:
            bad.append(f"| `{suite.get('name')}` | {t} | {f} | {e} | {s} |")

    verdict = "✅" if not (failures or errors) else "❌"
    print(f"### {verdict} {label}: {total} tests, {failures} failures, {errors} errors, {skipped} skipped\n")
    if bad:
        print("| Failing class | tests | failures | errors | skipped |")
        print("|---|---|---|---|---|")
        print("\n".join(bad))
    return 0


if __name__ == "__main__":
    sys.exit(main())
