# 🧾 easy-e-rechnung

**Java app for creating and validating Factur-X / ZUGFeRD / XRechnung invoices conforming to the EU norm EN 16931.**

Three ways to an e-invoice:

| Way | Input | Visible PDF | Typical use |
|-----|-------|-------------|-------------|
| **Excel → e-invoice** | Excel workbook (seller, customers, one sheet per invoice) | rendered by the app | write invoices directly as e-invoices |
| **Excel + PDF → e-invoice** | Excel workbook + your existing invoice PDF (e.g. from Word) | your PDF, look unchanged | keep your own layout, add the legally binding XML |
| **OCR (classic app)** | any invoice PDF | your PDF | extract data from PDFs with local AI (Ollama) |

---

## ✨ Key Features

| Feature | Description |
|---------|-------------|
| 🇪🇺 **EU compliant** | ZUGFeRD 2 / Factur-X profile **EN 16931** (PDF/A-3 with embedded `factur-x.xml`), optionally **XRechnung** (CII). |
| 📊 **Excel input** | Seller data once in a *Setup* sheet, customers in *Kunden*, one sheet per invoice with lines of type *Stunde* (hours) or *Stück* (pieces). |
| 🖨️ **Keeps your layout** | Existing PDFs are not re-rendered; only the XML is embedded. Missing text mappings of Word ligatures (ti/tt/ft) are repaired so the text layer stays searchable. |
| 🧾 **§ 19 UStG (Kleinunternehmer)** | Tax category E with exemption reason in the XML and a visible note on the PDF. Standard VAT (category S) works as well. |
| ✅ **Official validators** | Mustang (built in), KoSIT validator and veraPDF, individually switchable, with a traffic light per validator. |
| 🛑 **Nothing is guessed** | Missing invoice number, date, tax number or total, or inconsistent VAT data abort with a clear message (sheet and row) instead of producing a “valid-looking” invoice. |
| 🔒 **Local & private** | All processing happens on your machine. |
| 🤖 **LocalAI-powered OCR** | The classic app extracts invoice data from PDFs with local open-weight models (Ollama). |

---

## 🚀 Setup

Requirements: **Java 17+**. The Gradle wrapper is included.

```bash
# macOS / Linux: install Ollama + models for the OCR app (optional for the Excel flows)
bash setup.sh
```

Optional validators for the switchable checks are looked up under `%LOCALAPPS%` (default `C:\localapps`):

| Tool | Expected location |
|------|-------------------|
| [KoSIT validator](https://github.com/itplr-kosit/validator) + [XRechnung configuration](https://github.com/itplr-kosit/validator-configuration-xrechnung) | `kosit-validator\validator-*-standalone.jar`, `kosit-validator\scenarios.xml` |
| [veraPDF](https://verapdf.org/software/) (greenfield CLI) | `verapdf\bin\`, `verapdf\etc\` |

Mustang is built in and always available.

## 🧾 Usage

```bash
./gradlew run          # or: build/install/easy-e-rechnung/bin/easy-e-rechnung(.bat) after ./gradlew installDist
./start.sh             # macOS / Linux
start.bat              # Windows (sets JAVA_HOME, bun and Ollama for the OCR app)
```

The window **“E-Rechnungen erstellen”** opens:

![E-Rechnungen erstellen](docs/easy_erechnung_excel_flow.png)

1. **Choose the Excel file** (button or drag & drop). *Neu laden* picks up changes you saved in Excel meanwhile.
2. Choose the **output folder** and whether an additional **XRechnung XML** should be written (only for invoices with a buyer reference, BT-10).
3. Switch the **validators** on or off (Mustang on by default; KoSIT/veraPDF are greyed out when not installed).
4. **Tab “Excel → Rechnung”:** select the sheets → *Rechnungen erzeugen*.
   **Tab “Excel + PDF → Rechnung”:** assign the original PDF per sheet (double-click, drag & drop onto the row, or *PDF-Ordner wählen* for an automatic suggestion by invoice number / month in the file name) → *Rechnungen erzeugen*.
5. The result list shows one traffic light per active validator; *PDF öffnen*, *Ordner öffnen*, *Prüfbericht öffnen*.

For **Excel + PDF** the app warns when the invoice number or the amount due of the sheet are not printed on the chosen PDF, and refuses PDFs that already contain an e-invoice, are encrypted, or have no text layer (scans, where the § 19 note cannot be placed).

*Datei → Excel-Vorlage speichern* writes the template with fictitious sample data; *Extras → Alte OCR-Oberfläche* starts the classic OCR app.

### 📊 Excel format

A template with fictitious data is in [`templates/excel/rechnungen-vorlage.xlsx`](templates/excel/rechnungen-vorlage.xlsx) (regenerate with `./gradlew excelVorlage`).

| Sheet | Content |
|-------|---------|
| `Setup` | column A name, column B value: `Name`, `Straße`, `PLZ`, `Ort`, `Land`, `Steuernummer`, `USt-IdNr`, `E-Mail`, `Telefon`, `IBAN`, `BIC`, `Kontoinhaber`, `Kleinunternehmer` (ja/nein), `Umsatzsteuer %`, `Zahlungsziel Tage`, `Befreiungsgrund` |
| `Kunden` | one customer per row: `Kürzel`, `Name`, `Straße`, `PLZ`, `Ort`, `Land`, `Ansprechpartner`, `E-Mail`, `Käuferreferenz` |
| any other sheet | **one invoice**. Head (name/value): `Rechnungsnummer`, `Rechnungsdatum`, `Kunde` (Kürzel), optional `Bestellnummer`, `Käuferreferenz`, `Leistungszeitraum von`/`bis` (default: month of the invoice number or date), `Fälligkeit`, `Gesamtbetrag` (control sum). Then the item table `Datum \| Typ \| Beschreibung \| Menge \| Einzelpreis` with `Typ` = `Stunde` or `Stück` (a UN/ECE unit code is accepted too). |

Sheets whose name starts with `_` are ignored (e.g. `_Anleitung`). A sheet with errors is reported (red in the app) and does not stop the other invoices.

### 📁 Output

```
<output>/Rechnung_<Nr>/
  Rechnung_<Nr>.pdf              ← the final e-invoice (ZUGFeRD / Factur-X EN 16931, PDF/A-3)
  Rechnung_<Nr>-xrechnung.xml    ← only if XRechnung was requested and a buyer reference exists
  _pruefung/                     validator reports + zusammenfassung.txt
  _zwischenschritte/             original PDF, intermediate PDFs, factur-x.xml
```

Files are produced in a staging folder and only moved into place when everything succeeded — a failed run never destroys the previous valid invoice. Only the files above are replaced.

---

## 🧑‍💻 Command line

```bash
# Excel → e-invoices (own layout)
./gradlew excelRechnungen -Pexcel=rechnungen.in/rechnungen.xlsx [-PoutDir=rechnungen.out] [-Psheet=2026-08]

# existing PDFs in rechnungen.in + manually verified JSON (rechnungen.out/verified/<pdf-name>.json) → e-invoices
./gradlew convertRechnungen [-PinDir=…] [-PoutDir=…] [-PjsonDir=…]

# optional switches for both: -Pxrechnung -Pkosit -Pverapdf   (Mustang always runs)

./gradlew excelVorlage      # write the Excel template
./gradlew test              # unit tests (Mustang only)

# validate any e-invoice PDF or XML with Mustang CLI, KoSIT and veraPDF (Git Bash)
scripts/validate-all.sh <report-dir> <file.pdf|file.xml>...
```

`scripts/validate-all.sh` expects `C:\localapps\mustang\Mustang-CLI-2.26.0.jar` in addition to the tools above.

## 🤖 OCR pipeline via shell

```bash
# Single-page PDF
bun run src/ocr.ts --input demo/verify.pdf --output /tmp/result.json \
  --seller-address "Friedrich-Damm-Str. 8, 80999 München" \
  --seller-tax-no "147/214/00001"

# Multi-page PDF with custom models
bun run src/ocr.ts --input path/to/multipage.pdf --output /tmp/result.json \
  --seller-address "Friedrich-Damm-Str. 8, 80999 München" \
  --seller-tax-no "147/214/00001" \
  --ocr-model glm-ocr:q8_0 --json-model qwen3:4b-q8_0

# PDFs with a text layer: skip OCR, map the text directly (local or OpenAI-compatible endpoint)
bun run src/pdf-text-to-json.ts --input rechnungen.in --output rechnungen.out/text-pipeline
```

LLM output must be **checked by a human** before it becomes an invoice: copy the corrected JSON to `rechnungen.out/verified/` — `convertRechnungen` only reads from there.

### OCR pipeline architecture

1. **PDF → Images** — each page is rendered as a high-resolution image.
2. **Image preprocessing** — contrast boost, normalization, resize to max 3 MP.
3. **OCR per page** — vision model (`glm-ocr:q8_0`) extracts text as markdown.
4. **Date preprocessing** — date ranges are annotated with day counts (e.g. `DAYS: 31`) so the LLM sets quantities of time-based items correctly.
5. **JSON extraction** — all page markdowns go into one LLM call (`qwen3:4b-q8_0`) producing ZUGFeRD-compatible JSON.

---

## 📸 Classic OCR app

<details>
<summary>Step-by-step screenshots</summary>

### Step 1: Drag & drop your invoice PDF

Multi-page PDFs are displayed with tabs on the left side. The AI-powered OCR extracts the text of each page.

![OCR Detection](docs/easy_erechnung_app_ocr_detection.png)

### Step 2: AI post-processing

The local model extracts all relevant invoice data, with per-page OCR status tabs and a JSON extraction log.

![AI OCR Post-Processing](docs/easy_erechnung_app_ai_ocr_post.png)

### Step 3: Review invoice positions

![Invoice Positions](docs/easy_erechnung_app_positions.png)

### Step 4: Review taxes & totals

![Taxes and Totals](docs/easy_erechnung_app_taxes.png)

### Step 5: Create the e-invoice

![Create e-Invoice](docs/easy_erechnung_app_factur-x_zugferd_create.png)

### Step 6: Invoice created successfully

The app confirms the creation and opens the ELSTER e-Rechnung portal for official validation.

![Success](docs/easy_erechnung_app_successful_creation.png)

### Step 7: Final output

![Done](docs/easy_erechnung_app_done.png)

</details>

---

## ✅ Validated by official tools

- **Mustang** (built in), **KoSIT validator** with the XRechnung configuration (scenarios *EN16931 (CII)* and *EN16931 XRechnung*), **veraPDF** (PDF/A-3b) — see above.
- ELSTER and Winball:

![Validated by ELSTER](docs/elster_validated.png)

![Validated by Winball](docs/winball_validated.png)

---

## 🧩 RechnungFertig

For users of the Electron app *RechnungFertig* (1.8.1), [`templates/rechnungfertig/`](templates/rechnungfertig/) contains:

- `template_service_kleinunternehmer.docx` (+ generator `gen-template.ts`): service invoice template without ligatures, § 19 note instead of a 0 % VAT line, service period, formatted IBAN;
- `patch-rechnungfertig.ts`: local patch so that the purchase order number (BT-13) and the buyer contact (BT-56) end up in the XML — re-run after every app update;
- `UPSTREAM-ISSUES.md`: the corresponding bug reports.

---

## 🪟 Windows notes

- Java 17 on Windows defaults to Cp1252; the start scripts and Gradle tasks set `-Dfile.encoding=UTF-8`, and all files are read/written as UTF-8 explicitly.
- The generated Windows start script uses `lib\*` as classpath (the full jar list exceeds the cmd line length limit).

---

## 📁 Folders

| Folder | Content |
|--------|---------|
| `rechnungen.in/`, `rechnungen.out/` | your invoices and results (git-ignored) |
| `templates/` | Excel template, RechnungFertig template and patch |
| `demo/` | sample PDFs for the OCR pipeline |
| `scripts/` | validation scripts |

---

## 📜 License

MIT, Open Source.

---

## 🛡️ Privacy

- **Local processing.** The Excel/PDF flows and the validators run entirely on your machine.
- **Local AI by default.** OCR and JSON extraction use Ollama locally; `pdf-text-to-json.ts` can optionally be pointed to a remote OpenAI-compatible endpoint (e.g. OpenRouter) — only if you configure it.
- **No telemetry.** Your data stays on your device.
- **Open Source.** Audit the code yourself.
