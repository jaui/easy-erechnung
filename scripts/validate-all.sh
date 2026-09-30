#!/usr/bin/env bash
# Validates e-invoices with the official tools installed under C:\localapps:
#   Mustang CLI (XML schema/schematron + PDF/A via veraPDF), KoSIT validator (XRechnung
#   configuration: scenarios "EN16931 (CII)", "EN16931 XRechnung (UBL Invoice)", ...) and
#   veraPDF (PDF/A-3b).
# Accepts ZUGFeRD/Factur-X PDFs (embedded XML is extracted) and standalone CII/UBL XML files.
# Usage: scripts/validate-all.sh <out-dir> <file.pdf|file.xml>...
set -u
shopt -s nocasematch
LOCALAPPS=${LOCALAPPS:-C:/localapps}
MUSTANG=$LOCALAPPS/mustang/Mustang-CLI-2.26.0.jar
KOSIT=$LOCALAPPS/kosit-validator
VERAPDF=$LOCALAPPS/verapdf

out=$1; shift
mkdir -p "$out"
fail=0
for src in "$@"; do
  base=$(basename "$src")
  if [[ "$src" == *.pdf ]]; then
    n="${base%.*}-pdf"; xml="$out/$n-factur-x.xml"
  else
    n="${base%.*}-xml"; xml="$out/$n.xml"; cp "$src" "$xml"
  fi
  java -jar "$MUSTANG" --no-notices --action validate --source "$src" > "$out/$n-mustang.xml" 2>/dev/null
  if [[ "$src" == *.pdf ]]; then
    rm -f "$xml"
    java -jar "$MUSTANG" --action extract --source "$src" --out "$xml" > /dev/null 2>&1
  fi
  # KoSIT's stdin check fails on Windows without a real stdin, hence empty.in
  java -jar "$KOSIT"/validator-*-standalone.jar -s "$KOSIT/scenarios.xml" -r "$KOSIT" -o "$out" -h \
    "$xml" < "$KOSIT/empty.in" > "$out/$n-kosit.log" 2>&1

  # the last <summary> is the overall verdict; a crashed or empty run has none and counts as invalid
  mustang=$(grep -o '<summary status="[a-z]*"' "$out/$n-mustang.xml" | tail -1 | grep -q '"valid"' && echo valid || echo invalid)
  mustang_msgs=$(grep -o '<error\|<warning' "$out/$n-mustang.xml" | wc -l)
  kosit=$(grep -q 'Acceptable:  1' "$out/$n-kosit.log" && echo ACCEPTABLE || echo REJECT)
  x=$(basename "$xml" .xml)
  scenario=$(cat "$out/$x-report.xml" "$out/${x// /%20}-report.xml" 2>/dev/null | grep -o "<s:name>[^<]*" | head -1 | cut -c9-)
  line="$n: Mustang=$mustang (errors/warnings: $mustang_msgs) | KoSIT=$kosit [$scenario]"
  ok=1
  [ "$mustang" = valid ] && [ "$kosit" = ACCEPTABLE ] || ok=0
  if [[ "$src" == *.pdf ]]; then
    java -cp "$VERAPDF/etc;$VERAPDF/bin/*" --add-exports=java.base/sun.security.pkcs=ALL-UNNAMED \
      org.verapdf.apps.GreenfieldCliWrapper --flavour 3b --format xml "$src" > "$out/$n-verapdf-3b.xml" 2>/dev/null
    vera=$(grep -o 'isCompliant="[a-z]*"' "$out/$n-verapdf-3b.xml" | head -1)
    line="$line | veraPDF 3b ${vera:-missing}"
    [ "$vera" = 'isCompliant="true"' ] || ok=0
  fi
  echo "$line"
  [ $ok = 1 ] || fail=1
done
exit $fail
