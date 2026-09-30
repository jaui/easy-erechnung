// Usage: unzip RechnungFertig's uploads/template_service.docx into <dir>, run
//   bun templates/rechnungfertig/gen-template.ts <dir>
// then zip <dir> back into a .docx (e.g. `jar -cfM out.docx -C <dir> .`) and upload it in RechnungFertig.
// The built result is template_service_kleinunternehmer.docx next to this script.
// Generates a clean RechnungFertig service template (docx-templates syntax, { } delimiters).
const dir = process.argv[2];
const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
type R = { t: string; b?: boolean; sz?: number; color?: string };
const run = (r: R) =>
  `<w:r><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial"/>${r.b ? "<w:b/>" : ""}${r.color ? `<w:color w:val="${r.color}"/>` : ""}<w:sz w:val="${(r.sz ?? 10) * 2}"/><w:szCs w:val="${(r.sz ?? 10) * 2}"/><w14:ligatures w14:val="none"/></w:rPr><w:t xml:space="preserve">${esc(r.t)}</w:t></w:r>`;
const p = (runs: (R | string)[], o: { align?: string; after?: number; before?: number; keep?: boolean } = {}) =>
  `<w:p><w:pPr>${o.keep ? "<w:keepNext/>" : ""}<w:spacing w:before="${o.before ?? 0}" w:after="${o.after ?? 0}" w:line="264" w:lineRule="auto"/>${o.align ? `<w:jc w:val="${o.align}"/>` : ""}</w:pPr>${runs.map(r => run(typeof r === "string" ? { t: r } : r)).join("")}</w:p>`;
const cell = (w: number, content: string, o: { borders?: string; span?: number } = {}) =>
  `<w:tc><w:tcPr><w:tcW w:w="${w}" w:type="dxa"/>${o.span ? `<w:gridSpan w:val="${o.span}"/>` : ""}${o.borders ?? ""}</w:tcPr>${content}</w:tc>`;
const row = (cells: string, header = false) => `<w:tr><w:trPr><w:cantSplit/>${header ? "<w:tblHeader/>" : ""}</w:trPr>${cells}</w:tr>`;
const noBorders = "<w:tblBorders><w:top w:val=\"nil\"/><w:left w:val=\"nil\"/><w:bottom w:val=\"nil\"/><w:right w:val=\"nil\"/><w:insideH w:val=\"nil\"/><w:insideV w:val=\"nil\"/></w:tblBorders>";
const table = (widths: number[], rows: string) =>
  `<w:tbl><w:tblPr><w:tblW w:w="${widths.reduce((a, b) => a + b)}" w:type="dxa"/><w:tblLayout w:type="fixed"/>${noBorders}<w:tblCellMar><w:left w:w="0" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid>${widths.map(w => `<w:gridCol w:w="${w}"/>`).join("")}</w:tblGrid>${rows}</w:tbl>`;
const bottomLine = "<w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/></w:tcBorders>";
const headLines = "<w:tcBorders><w:top w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"000000\"/><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"000000\"/></w:tcBorders>";
const topLine = "<w:tcBorders><w:top w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"000000\"/></w:tcBorders>";
const grey = "555555";

// --- head: address window (left) + info block (right)
const info: [string, string][] = [
  ["Rechnungs-Nr.:", "{rnr}"],
  ["Rechnungsdatum:", "{fdate(rdatum)}"],
  ["Leistungszeitraum:", "{fdate(bt73)} – {fdate(bt74)}"],
  ["Bestell-Nr.:", "{rbestellnr}"],
  ["Steuernummer:", "{vsteuernr}"],
];
const head = table([5450, 1720, 2468], row(
  cell(5450,
    p([{ t: "{vname} · {vadresse1} · {vplz} {vort}", sz: 7.5, color: grey }], { after: 160 }) +
    p(["{kname}"]) +
    p(["{IF kaltname1}"]) + p(["z. Hd. {kaltname1}"]) + p(["{END-IF}"]) +
    p(["{kadresse1}"]) +
    p(["{kplz} {kort}"])) +
  cell(1720, info.map(([l]) => p([{ t: l, color: grey, sz: 9 }])).join("")) +
  cell(2468, info.map(([, v]) => p([{ t: v, sz: 9 }])).join(""))
));

// --- positions
const W = [650, 4588, 1000, 800, 1300, 1300];
const hdr = ["Pos.", "Leistung", "Menge", "Einheit", "Einzelpreis", "Summe"];
const align = ["left", "left", "right", "left", "right", "right"];
const vals = ["{$p.anr}", "{$p.aname}", "{Number(String($p.amenge).replace(',', '.')).toLocaleString('de-DE')}", "{$p.aeinheit}", "{fnum($p.anetto)} €", "{fnum($p.asum)} €"];
const total = W.reduce((a, b) => a + b);
const loopRow = (t: string) => row(cell(total, p([t]), { span: 6 }));
const positions = table(W,
  row(W.map((w, i) => cell(w, p([{ t: hdr[i], b: true }], { align: align[i], before: 40, after: 40 }), { borders: headLines })).join(""), true) +
  loopRow("{FOR p in positions}") +
  row(W.map((w, i) => cell(w,
    p([vals[i]], { align: align[i], before: 60, after: i === 1 ? 0 : 60 }) +
    (i === 1 ? p([{ t: "{IF $p.aprodinfo}{$p.aprodinfo}{END-IF}", sz: 8.5, color: grey }], { after: 60 }) : ""),
    { borders: bottomLine })).join("")) +
  loopRow("{END-FOR p}"));

// --- totals
const T = [5238, 2600, 1800];
const trow = (l: R | string, v: R | string, borders = "") =>
  row(cell(T[0], p([""])) + cell(T[1], p([l], { before: 40, after: 40 }), { borders }) + cell(T[2], p([v], { align: "right", before: 40, after: 40 }), { borders }));
const cmdRow = (t: string) => row(cell(T.reduce((a, b) => a + b), p([t]), { span: 3 }));
const totals = table(T,
  trow("Summe netto", "{fnum(sumnet)} €") +
  cmdRow("{IF abschlaege.length > 0}") + trow("Abschläge", "{fnum(sumabzug)} €") + cmdRow("{END-IF}") +
  cmdRow("{IF steuerkategoriecode !== 'E'}") + trow("Umsatzsteuer {steuerp} %", "{fnum(vat)} €") + cmdRow("{END-IF}") +
  trow({ t: "Rechnungsbetrag", b: true }, { t: "{fnum(zuzahlen)} €", b: true }, topLine));

const body =
  head +
  p([{ t: "Rechnung {rnr}", b: true, sz: 16 }], { before: 600, after: 240 }) +
  positions +
  totals +
  p([{ t: "{IF befreiungsgrund}{befreiungsgrund}{END-IF}", b: true }], { before: 240, after: 120 }) +
  p(["Bitte überweisen Sie den Rechnungsbetrag bis zum {fdate(zfälligkeit)} unter Angabe der Rechnungsnummer auf das unten genannte Konto."], { after: 120 }) +
  p(["{IF zbedingungen}{zbedingungen}{END-IF}"], { after: 240 }) +
  p(["Mit freundlichen Grüßen"], { after: 120, keep: true }) +
  p(["{vname}"]);

const NS = 'xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:w10="urn:schemas-microsoft-com:office:word" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing" xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml" mc:Ignorable="w14 wp14"';
const sect = '<w:sectPr><w:footerReference w:type="default" r:id="rId2"/><w:type w:val="nextPage"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:left="1134" w:right="1134" w:header="0" w:top="1134" w:footer="454" w:bottom="1700" w:gutter="0"/><w:pgNumType w:fmt="decimal"/><w:formProt w:val="false"/><w:textDirection w:val="lrTb"/><w:docGrid w:type="default" w:linePitch="100" w:charSpace="0"/></w:sectPr>';
await Bun.write(`${dir}/word/document.xml`, `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<w:document ${NS}><w:body>${body}${sect}</w:body></w:document>`);

// --- footer: 3 columns + page number
const F = [3212, 3213, 3213];
const fp = (t: string, b = false) => p([{ t, sz: 7.5, b, color: b ? "000000" : "444444" }]);
const ibanExpr = "{(viban_1 || '').split(' ').join('').split('').map((c, i) => (i > 0 && i % 4 === 0 ? ' ' : '') + c).join('')}";
const footer = table(F, row(
  cell(F[0], fp("{vname}", true) + fp("{vadresse1}") + fp("{vplz} {vort}")) +
  cell(F[1], fp("Kontakt & Steuer", true) + fp("E-Mail: {vemail}{IF vtelefon} · Tel. {vtelefon}{END-IF}") + fp("Steuernummer: {vsteuernr}") + fp("{IF vvatid}USt-IdNr.: {vvatid}{END-IF}")) +
  cell(F[2], fp("Bankverbindung", true) + fp("Kontoinhaber: {vbankname_1}") + fp("IBAN: " + ibanExpr) + fp("BIC: {vbic_1}"))
));
const rule = '<w:p><w:pPr><w:pBdr><w:top w:val="single" w:sz="4" w:space="4" w:color="999999"/></w:pBdr><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>';
const fld = (x: string) => `<w:r><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/><w:sz w:val="14"/></w:rPr>${x}</w:r>`;
const pageNo = `<w:p><w:pPr><w:jc w:val="right"/></w:pPr>${fld('<w:t xml:space="preserve">Seite </w:t>')}${fld('<w:fldChar w:fldCharType="begin"/>')}${fld('<w:instrText xml:space="preserve"> PAGE </w:instrText>')}${fld('<w:fldChar w:fldCharType="separate"/>')}${fld("<w:t>1</w:t>")}${fld('<w:fldChar w:fldCharType="end"/>')}</w:p>`;
await Bun.write(`${dir}/word/footer1.xml`, `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<w:ftr ${NS}>${rule}${footer}${pageNo}</w:ftr>`);

// --- styles: Arial, ligatures explicitly off
let styles = await Bun.file(`${dir}/word/styles.xml`).text();
if (!styles.includes("xmlns:w14=")) styles = styles.replace("<w:styles ", '<w:styles xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml" ');
styles = styles.replace(/<w:rPrDefault><w:rPr><w:rFonts[^>]*\/>/, '<w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:eastAsia="Arial" w:cs="Arial"/>');
if (!styles.includes("w14:ligatures")) styles = styles.replace("</w:rPr></w:rPrDefault>", '<w14:ligatures w14:val="none"/></w:rPr></w:rPrDefault>');
await Bun.write(`${dir}/word/styles.xml`, styles);
console.log("ok");
