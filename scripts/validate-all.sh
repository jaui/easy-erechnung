#!/usr/bin/env bash
# Validates e-invoices with the official tools:
#   Mustang CLI (XML schema/schematron + PDF/A via veraPDF), KoSIT validator (XRechnung
#   configuration: scenarios "EN16931 (CII)", "EN16931 XRechnung (UBL Invoice)", ...) and
#   veraPDF (PDF/A-3b).
# Accepts ZUGFeRD/Factur-X PDFs (embedded XML is extracted) and standalone CII/UBL XML files.
#
# Usage: scripts/validate-all.sh <out-dir> <file.pdf|file.xml>...
#
# Tools are looked up per tool in this order (the first folder containing it wins):
#   1. $LOCALAPPS (if set)
#   2. user folder: Windows %LOCALAPPDATA%/easy-e-rechnung/tools,
#      macOS ~/Library/Application Support/easy-e-rechnung/tools,
#      Linux ${XDG_DATA_HOME:-~/.local/share}/easy-e-rechnung/tools
#   3. <repo>/tools (filled by ./gradlew pruefprogrammeLaden)
#   4. C:/localapps (Windows) or ~/localapps (macOS/Linux)
# Layout inside a folder: kosit-validator/{validator-*-standalone.jar,scenarios.xml},
#   verapdf/bin/cli-*.jar, mustang/Mustang-CLI-*.jar
#
# A missing tool is reported as MISSING and skipped. Exit code: 0 all present checks passed,
# 1 a present check failed, 2 usage error or no tool found at all.
# Works with bash 3.2 (macOS), Linux and Git Bash on Windows.
set -u
shopt -s nocasematch

if [ $# -lt 2 ]; then
  echo "Usage: $0 <out-dir> <file.pdf|file.xml>..." >&2
  exit 2
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) windows=1; sep=';' ;;
  *) windows=0; sep=':' ;;
esac
darwin=0
[ "$(uname -s)" = Darwin ] && darwin=1

# Java on Windows needs Windows paths (C:/...), not /c/...
native() {
  if [ $windows = 1 ] && command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

repo_dir=$(cd "$(dirname "$0")/.." && pwd)

roots=()
[ -n "${LOCALAPPS:-}" ] && roots+=("$LOCALAPPS")
if [ $windows = 1 ]; then
  [ -n "${LOCALAPPDATA:-}" ] && roots+=("$LOCALAPPDATA/easy-e-rechnung/tools")
elif [ $darwin = 1 ]; then
  roots+=("$HOME/Library/Application Support/easy-e-rechnung/tools")
else
  roots+=("${XDG_DATA_HOME:-$HOME/.local/share}/easy-e-rechnung/tools")
fi
roots+=("$repo_dir/tools")
if [ $windows = 1 ]; then roots+=("C:/localapps"); else roots+=("$HOME/localapps"); fi

# first file matching a glob (sorted by the shell), empty if none
first_match() {
  for f in "$@"; do [ -e "$f" ] && { printf '%s\n' "$f"; return; }; done
}
# last file matching a glob (highest version for equal-width names), empty if none
last_match() {
  last=""
  for f in "$@"; do [ -e "$f" ] && last=$f; done
  printf '%s\n' "$last"
}

MUSTANG=""; KOSIT=""; KOSIT_JAR=""; VERAPDF=""
for r in "${roots[@]}"; do
  if [ -z "$MUSTANG" ]; then
    MUSTANG=$(last_match "$r"/mustang/Mustang-CLI-*.jar)
  fi
  if [ -z "$KOSIT" ] && [ -f "$r/kosit-validator/scenarios.xml" ]; then
    KOSIT_JAR=$(first_match "$r"/kosit-validator/validator-*-standalone.jar)
    [ -n "$KOSIT_JAR" ] && KOSIT="$r/kosit-validator"
  fi
  if [ -z "$VERAPDF" ] && [ -n "$(first_match "$r"/verapdf/bin/cli-*.jar)" ]; then
    VERAPDF="$r/verapdf"
  fi
done

missing=""
[ -n "$MUSTANG" ] || missing="$missing mustang"
[ -n "$KOSIT" ] || missing="$missing kosit"
[ -n "$VERAPDF" ] || missing="$missing verapdf"
if [ -n "$missing" ]; then
  list=$(echo $missing | tr ' ' ',')
  {
    echo "Missing validator(s):$missing - these checks are skipped (reported as MISSING)."
    echo "  Searched: ${roots[*]}"
    echo "  Install:  ./gradlew pruefprogrammeLaden -Ptools=$list"
  } >&2
fi
if [ -z "$MUSTANG" ] && [ -z "$KOSIT" ] && [ -z "$VERAPDF" ]; then
  echo "No validator found, nothing to do." >&2
  exit 2
fi

[ -n "$MUSTANG" ] && MUSTANG=$(native "$MUSTANG")
[ -n "$KOSIT" ] && { KOSIT=$(native "$KOSIT"); KOSIT_JAR=$(native "$KOSIT_JAR"); }
[ -n "$VERAPDF" ] && VERAPDF=$(native "$VERAPDF")

# KoSIT's stdin check fails on Windows without a real stdin, hence an empty file as stdin
empty_in=$(mktemp "${TMPDIR:-/tmp}/validate-all.XXXXXX") || exit 2
trap 'rm -f "$empty_in"' EXIT

out=$1; shift
mkdir -p "$out"
fail=0; total=0; failed=0
for src in "$@"; do
  total=$((total + 1))
  base=$(basename "$src")
  if [[ "$src" == *.pdf ]]; then
    n="${base%.*}-pdf"; xml="$out/$n-factur-x.xml"
  else
    n="${base%.*}-xml"; xml="$out/$n.xml"; cp "$src" "$xml"
  fi
  ok=1

  if [ -n "$MUSTANG" ]; then
    java -jar "$MUSTANG" --no-notices --action validate --source "$src" > "$out/$n-mustang.xml" 2>/dev/null
    if [[ "$src" == *.pdf ]]; then
      rm -f "$xml"
      java -jar "$MUSTANG" --action extract --source "$src" --out "$xml" > /dev/null 2>&1
    fi
    # the last <summary> is the overall verdict; a crashed or empty run has none and counts as invalid
    mustang=$(grep -o '<summary status="[a-z]*"' "$out/$n-mustang.xml" | tail -1 | grep -q '"valid"' && echo valid || echo invalid)
    mustang_msgs=$(grep -o '<error\|<warning' "$out/$n-mustang.xml" | wc -l | tr -d ' ')
    line="$n: Mustang=$mustang (errors/warnings: $mustang_msgs)"
    [ "$mustang" = valid ] || ok=0
  else
    line="$n: Mustang=MISSING"
  fi

  if [ -z "$KOSIT" ]; then
    line="$line | KoSIT=MISSING"
  elif [ ! -f "$xml" ]; then
    # the embedded XML of a PDF is extracted with Mustang
    line="$line | KoSIT=MISSING [no XML: extraction needs Mustang]"
  else
    java -jar "$KOSIT_JAR" -s "$KOSIT/scenarios.xml" -r "$KOSIT" -o "$(native "$out")" -h \
      "$(native "$xml")" < "$empty_in" > "$out/$n-kosit.log" 2>&1
    kosit=$(grep -q 'Acceptable:  1' "$out/$n-kosit.log" && echo ACCEPTABLE || echo REJECT)
    x=$(basename "$xml" .xml)
    scenario=$(cat "$out/$x-report.xml" "$out/${x// /%20}-report.xml" 2>/dev/null | grep -o "<s:name>[^<]*" | head -1 | cut -c9-)
    line="$line | KoSIT=$kosit [$scenario]"
    [ "$kosit" = ACCEPTABLE ] || ok=0
  fi

  if [[ "$src" == *.pdf ]]; then
    if [ -n "$VERAPDF" ]; then
      java -cp "$VERAPDF/etc$sep$VERAPDF/bin/*" --add-exports=java.base/sun.security.pkcs=ALL-UNNAMED \
        org.verapdf.apps.GreenfieldCliWrapper --flavour 3b --format xml "$src" > "$out/$n-verapdf-3b.xml" 2>/dev/null
      vera=$(grep -o 'isCompliant="[a-z]*"' "$out/$n-verapdf-3b.xml" | head -1)
      line="$line | veraPDF 3b ${vera:-missing}"
      [ "$vera" = 'isCompliant="true"' ] || ok=0
    else
      line="$line | veraPDF 3b MISSING"
    fi
  fi

  echo "$line"
  if [ $ok = 0 ]; then fail=1; failed=$((failed + 1)); fi
done

summary="$total file(s): $((total - failed)) passed, $failed failed"
[ -n "$missing" ] && summary="$summary; not checked with:$missing (./gradlew pruefprogrammeLaden -Ptools=$list)"
echo "$summary"
exit $fail
