#!/usr/bin/env bun
/**
 * PDF text → invoice JSON (no vision OCR)
 *
 * Extracts embedded text from a text PDF, then maps it to the same invoice JSON
 * schema used by `src/ocr.ts` via any OpenAI-compatible chat endpoint.
 *
 * Usage (single file):
 *   bun run src/pdf-text-to-json.ts \
 *     --input invoice.pdf --output result.json \
 *     --json-base-url https://openrouter.ai/api \
 *     --json-api-key-file openrouter_key.txt \
 *     --json-model google/gemini-2.5-flash-lite
 *
 * Usage (directory batch):
 *   bun run src/pdf-text-to-json.ts \
 *     --input rechnungen.in --output rechnungen.out \
 *     --json-base-url https://openrouter.ai/api \
 *     --json-api-key-file openrouter_key.txt
 *
 * Env (CLI wins): JSON_BASE_URL  JSON_API_KEY  JSON_MODEL  JSON_REASONING
 */

import { readdirSync, readFileSync, writeFileSync, mkdirSync, statSync } from 'fs';
import { basename, dirname, extname, join, resolve } from 'path';
import { extractText, getDocumentProxy } from 'unpdf';

// ── Prompt (aligned with src/ocr.ts) ──────────────────────────────────────────

const SYSTEM_MESSAGE = `You are a strict data mapper. Your ONLY job is to copy values from the OCR text into the correct JSON fields.

CRITICAL RULES:
- NEVER calculate, derive, or infer any numeric value. Every number in the output must appear exactly as written in the document.
- Copy Quantity, UnitPrice, LineTotalAmount, TaxAmount, TaxPercentage, and all MonetarySummation values EXACTLY as they appear in the text.
- Do NOT recompute totals, do NOT multiply quantity × price, do NOT verify that line totals add up. Just copy the numbers.
- The OCR text may come from multiple pages separated by "--- PAGE N ---". Merge into ONE invoice.
- If a value is not found in the text: use "" for strings, 0.0 for numbers.
- Do NOT invent or hallucinate values.
- InvoiceNumber is NOT a LineID. Look for "Rechnungsnummer", "Invoice #", "RE-", "INV-" etc.
- Do NOT duplicate line items that appear on multiple pages.
- PaymentReference: only if explicitly stated as a distinct reference code; otherwise "".
- Unit: if a unit is explicitly stated (e.g. "Stunden", "Tage", "Stück"), map it to the corresponding UN/CEFACT code (HUR, DAY, C62). If no unit is stated, infer the most likely unit from the line item description context (e.g. hourly rate → HUR, daily rate → DAY, otherwise C62). Never calculate a unit from date ranges or quantities.`;

function buildUserMessage(markdown: string, mode: 'eingang' | 'ausgang'): string {
  return `MODE (hint only): ${mode.toUpperCase()}

OCR'd invoice text:
${markdown}

REMINDER: Copy ALL numbers exactly as written. Do NOT calculate or verify anything. Just map text to fields.

Return ONLY valid JSON matching exactly this structure:
{
  "Invoice": {
    "InvoiceNumber": "",
    "InvoiceDate": "YYYY-MM-DD",
    "DueDate": "YYYY-MM-DD",
    "Seller": {
      "Name": "",
      "StreetName": "",
      "City": "",
      "PostalCode": "",
      "CountryCode": "",
      "TaxIdentificationNumber": "",
      "TaxVATNumber": ""
    },
    "Buyer": {
      "Name": "",
      "StreetName": "",
      "City": "",
      "PostalCode": "",
      "CountryCode": "",
      "TaxIdentificationNumber": "",
      "TaxVATNumber": ""
    },
    "DocumentCurrencyCode": "EUR",
    "IBAN": "",
    "BIC": "",
    "BankName": "",
    "PaymentReceiver": "",
    "PaymentReference": "",
    "Tax": {
      "TaxTypeCode": "VAT",
      "TaxCategoryCode": "S",
      "TaxPercentage": 0.0,
      "TaxAmount": 0.0
    },
    "MonetarySummation": {
      "LineTotal": 0.0,
      "TaxExclusiveAmount": 0.0,
      "TaxInclusiveAmount": 0.0,
      "PayableAmount": 0.0
    },
    "InvoiceLines": [
      {
        "LineID": "1",
        "ProductName": "",
        "Unit": "HUR",
        "Quantity": 0.0,
        "UnitPrice": 0.0,
        "LineTotalAmount": 0.0,
        "TaxCategoryCode": "S",
        "TaxPercentage": 0.0
      }
    ]
  }
}`;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function extractJson(raw: string): string {
  let text = raw.trim();
  text = text.replace(/<think>[\s\S]*?<\/think>/gi, '').trim();
  text = text.replace(/^```(?:json)?\s*/i, '').replace(/\s*```\s*$/, '').trim();

  try {
    JSON.parse(text);
    return text;
  } catch {
    /* continue */
  }

  const start = text.indexOf('{');
  if (start === -1) throw new Error('LLM-Antwort enthält kein gültiges JSON-Objekt');

  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (ch === '\\' && inString) {
      escaped = true;
      continue;
    }
    if (ch === '"') {
      inString = !inString;
      continue;
    }
    if (inString) continue;
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) {
        const candidate = text.substring(start, i + 1);
        JSON.parse(candidate);
        return candidate;
      }
    }
  }

  throw new Error('LLM-Antwort enthält kein gültiges JSON-Objekt');
}

function normalizeBaseUrl(url: string): string {
  return url.replace(/\/v1\/?$/, '').replace(/\/$/, '');
}

function readApiKey(apiKey: string, apiKeyFile: string): string {
  if (apiKey.trim()) return apiKey.trim();
  if (apiKeyFile.trim()) {
    return readFileSync(apiKeyFile, 'utf8').trim();
  }
  return '';
}

export async function extractPdfText(pdfPath: string): Promise<{ pages: number; text: string }> {
  const data = new Uint8Array(readFileSync(pdfPath));
  const pdf = await getDocumentProxy(data);
  // keep pages separate so the page markers the mapping prompt expects are present
  const { totalPages, text } = await extractText(pdf, { mergePages: false });
  const merged = (text as string[]).map((page, i) => `--- PAGE ${i + 1} ---\n${page}`).join('\n\n');
  return { pages: totalPages, text: merged.trim() };
}

export async function markdownToInvoiceJson(opts: {
  markdown: string;
  mode: 'eingang' | 'ausgang';
  baseUrl: string;
  apiKey: string;
  model: string;
  reasoning?: boolean;
}): Promise<string> {
  const baseUrl = normalizeBaseUrl(opts.baseUrl);
  const url = `${baseUrl}/v1/chat/completions`;
  const t0 = Date.now();

  const response = await fetch(url, {
    method: 'POST',
    signal: AbortSignal.timeout(5 * 60_000),
    headers: {
      'Content-Type': 'application/json',
      ...(opts.apiKey ? { Authorization: `Bearer ${opts.apiKey}` } : {}),
    },
    body: JSON.stringify({
      model: opts.model,
      messages: [
        { role: 'system', content: SYSTEM_MESSAGE },
        { role: 'user', content: buildUserMessage(opts.markdown, opts.mode) },
      ],
      stream: false,
      temperature: 0,
      response_format: { type: 'json_object' },
      ...(opts.reasoning ? { reasoning_effort: 'high' } : {}),
    }),
  });

  console.log(`[JSON] LLM-Antwort in ${((Date.now() - t0) / 1000).toFixed(1)}s — HTTP ${response.status}`);

  if (!response.ok) {
    const body = await response.text().catch(() => '(no body)');
    throw new Error(`JSON-Extraktion fehlgeschlagen: HTTP ${response.status} — ${body}`);
  }

  const data = (await response.json()) as {
    choices?: Array<{ message?: { content?: string } }>;
  };
  const content = data.choices?.[0]?.message?.content;
  if (!content) throw new Error(`JSON-Modell (${opts.model}) lieferte eine leere Antwort`);
  return extractJson(content);
}

export async function pdfTextToInvoiceJson(opts: {
  pdfPath: string;
  mode: 'eingang' | 'ausgang';
  baseUrl: string;
  apiKey: string;
  model: string;
  reasoning?: boolean;
  saveTextPath?: string;
}): Promise<{ text: string; pages: number; json: string }> {
  console.log(`[TEXT] Extrahiere Text aus ${opts.pdfPath}`);
  const { pages, text } = await extractPdfText(opts.pdfPath);
  console.log(`[TEXT] ${pages} Seite(n), ${text.length} Zeichen`);

  if (!text.trim()) {
    throw new Error('Kein extrahierbarer Text im PDF — ggf. Scan; dann src/ocr.ts verwenden');
  }

  if (opts.saveTextPath) {
    mkdirSync(dirname(opts.saveTextPath), { recursive: true });
    writeFileSync(opts.saveTextPath, text, 'utf8');
    console.log(`[TEXT] Rohtext gespeichert: ${opts.saveTextPath}`);
  }

  console.log(`[JSON] Extrahiere Rechnungsdaten (Modell: ${opts.model})...`);
  const json = await markdownToInvoiceJson({
    markdown: text,
    mode: opts.mode,
    baseUrl: opts.baseUrl,
    apiKey: opts.apiKey,
    model: opts.model,
    reasoning: opts.reasoning,
  });

  return { text, pages, json };
}

// ── CLI ───────────────────────────────────────────────────────────────────────

interface CliArgs {
  input: string;
  output: string;
  mode: 'eingang' | 'ausgang';
  jsonBaseUrl: string;
  jsonApiKey: string;
  jsonApiKeyFile: string;
  jsonModel: string;
  jsonReasoning: boolean;
  saveText: boolean;
}

function printUsageAndExit(): never {
  console.error(`Verwendung:
  bun run src/pdf-text-to-json.ts --input <Datei|Ordner> --output <Datei|Ordner>
      [--mode eingang|ausgang]
      [--json-base-url <URL>] [--json-api-key <Key>] [--json-api-key-file <Pfad>]
      [--json-model <Modell>] [--json-reasoning true|false]
      [--save-text]

Env: JSON_BASE_URL  JSON_API_KEY  JSON_MODEL  JSON_REASONING`);
  process.exit(1);
}

function parseArgs(argv: string[]): CliArgs {
  const result: CliArgs = {
    input: '',
    output: '',
    mode: 'eingang',
    jsonBaseUrl: process.env.JSON_BASE_URL ?? 'https://openrouter.ai/api',
    jsonApiKey: process.env.JSON_API_KEY ?? '',
    jsonApiKeyFile: '',
    jsonModel: process.env.JSON_MODEL ?? 'google/gemini-2.5-flash-lite',
    jsonReasoning: process.env.JSON_REASONING === 'true',
    saveText: false,
  };

  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case '--input':
        result.input = argv[++i];
        break;
      case '--output':
        result.output = argv[++i];
        break;
      case '--mode':
        result.mode = argv[++i] as 'eingang' | 'ausgang';
        break;
      case '--json-base-url':
        result.jsonBaseUrl = argv[++i];
        break;
      case '--json-api-key':
        result.jsonApiKey = argv[++i];
        break;
      case '--json-api-key-file':
        result.jsonApiKeyFile = argv[++i];
        break;
      case '--json-model':
        result.jsonModel = argv[++i];
        break;
      case '--json-reasoning':
        result.jsonReasoning = argv[++i] === 'true';
        break;
      case '--save-text':
        result.saveText = true;
        break;
      case '--help':
      case '-h':
        printUsageAndExit();
      default:
        console.error(`Unbekanntes Argument: ${argv[i]}`);
        printUsageAndExit();
    }
  }

  if (!result.input || !result.output) printUsageAndExit();
  return result;
}

function listPdfInputs(inputPath: string): string[] {
  const st = statSync(inputPath);
  if (st.isFile()) {
    if (extname(inputPath).toLowerCase() !== '.pdf') {
      throw new Error(`Eingabe ist keine PDF-Datei: ${inputPath}`);
    }
    return [resolve(inputPath)];
  }
  if (st.isDirectory()) {
    return readdirSync(inputPath)
      .filter((f) => f.toLowerCase().endsWith('.pdf'))
      .map((f) => resolve(inputPath, f))
      .sort();
  }
  throw new Error(`Eingabe nicht gefunden: ${inputPath}`);
}

function resolveOutputPath(inputPdf: string, outputArg: string, inputIsDir: boolean): string {
  if (!inputIsDir && extname(outputArg).toLowerCase() === '.json') {
    return resolve(outputArg);
  }
  mkdirSync(outputArg, { recursive: true });
  return resolve(outputArg, `${basename(inputPdf, extname(inputPdf))}.json`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const apiKey = readApiKey(args.jsonApiKey, args.jsonApiKeyFile);
  if (!apiKey) {
    console.error('FEHLER: JSON API-Key fehlt (--json-api-key / --json-api-key-file / JSON_API_KEY)');
    process.exit(1);
  }

  const inputPath = resolve(args.input);
  const inputIsDir = statSync(inputPath).isDirectory();
  const pdfs = listPdfInputs(inputPath);
  if (pdfs.length === 0) {
    console.error(`Keine PDFs unter ${inputPath}`);
    process.exit(1);
  }

  console.log('=== PDF-Text → JSON ===');
  console.log(`Endpunkt : ${normalizeBaseUrl(args.jsonBaseUrl)}`);
  console.log(`Modell   : ${args.jsonModel}`);
  console.log(`Modus    : ${args.mode}`);
  console.log(`Dateien  : ${pdfs.length}`);
  console.log('');

  let failed = 0;
  for (const pdf of pdfs) {
    const outJson = resolveOutputPath(pdf, args.output, inputIsDir);
    const outText = args.saveText
      ? join(dirname(outJson), `${basename(pdf, extname(pdf))}.txt`)
      : undefined;

    console.log(`\n======== ${basename(pdf)} ========`);
    try {
      mkdirSync(dirname(outJson), { recursive: true });
      const { json } = await pdfTextToInvoiceJson({
        pdfPath: pdf,
        mode: args.mode,
        baseUrl: args.jsonBaseUrl,
        apiKey,
        model: args.jsonModel,
        reasoning: args.jsonReasoning,
        saveTextPath: outText,
      });
      writeFileSync(outJson, JSON.stringify(JSON.parse(json), null, 2), 'utf8');
      console.log(`[OK] ${outJson}`);
    } catch (err) {
      failed++;
      const msg = err instanceof Error ? err.message : String(err);
      console.error(`[FEHLER] ${basename(pdf)}: ${msg}`);
    }
  }

  if (failed > 0) {
    console.error(`\nFertig mit ${failed} Fehler(n).`);
    process.exit(1);
  }
  console.log('\nFertig.');
}

if (import.meta.main) {
  main().catch((err) => {
    console.error(err instanceof Error ? err.message : err);
    process.exit(1);
  });
}
