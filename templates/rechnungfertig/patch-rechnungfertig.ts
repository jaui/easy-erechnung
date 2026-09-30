// Local patch for RechnungFertig 1.8.1 (re-run after every app update; idempotent, keeps *.orig backups).
//   1. @e-invoice-eu/core 3.0.3: BT-13 (purchase order reference, cac:OrderReference/cbc:ID ->
//      ram:BuyerOrderReferencedDocument) is part of EN 16931 but was only emitted for EXTENDED.
//   2. RechnungFertig invoice.js: the buyer contact (BT-56) was never passed on; use the customer's
//      "Alternativer Name 1" (e.g. "Frau Muster", printed as "z. Hd.") as buyer contact name.
// Usage: bun templates/rechnungfertig/patch-rechnungfertig.ts [C:\dev\RechnungFertig-1.8.1-win]
import { existsSync, copyFileSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const appDir = process.argv[2] ?? "C:\\dev\\RechnungFertig-1.8.1-win";
const app = join(appDir, "resources", "app");

function patch(file: string, marker: string, from: string, to: string) {
  const text = readFileSync(file, "utf8");
  if (text.includes(marker)) return console.log("already patched:", file);
  const count = text.split(from).length - 1;
  if (count !== 1) throw new Error(`expected exactly one match in ${file}, found ${count} – app version changed?`);
  if (!existsSync(file + ".orig")) copyFileSync(file, file + ".orig");
  writeFileSync(file, text.replace(from, to));
  console.log("patched:", file);
}

patch(join(app, "node_modules", "@e-invoice-eu", "core", "dist", "e-invoice-eu.cjs.js"),
  "easy-erechnung patch: BT-13",
  `                    src: ['cac:OrderReference', 'cbc:ID'],
                    dest: [
                        'ram:ApplicableHeaderTradeAgreement',
                        'ram:BuyerOrderReferencedDocument',
                        'ram:IssuerAssignedID',
                    ],
                    fxProfileMask: FX_MASK_EXTENDED,`,
  `                    src: ['cac:OrderReference', 'cbc:ID'],
                    dest: [
                        'ram:ApplicableHeaderTradeAgreement',
                        'ram:BuyerOrderReferencedDocument',
                        'ram:IssuerAssignedID',
                    ],
                    fxProfileMask: FX_MASK_EN16931, // easy-erechnung patch: BT-13 is part of EN 16931`);

patch(join(app, "src", "invoice.js"),
  "easy-erechnung patch: BT-56",
  `        //console.log('invoice data for ZugFerd', invoice);`,
  `        // easy-erechnung patch: BT-56 buyer contact from "Alternativer Name 1"
        if (d.simpleBuyerTradeParty.altname1) {
            invoice["ubl:Invoice"]["cac:AccountingCustomerParty"]["cac:Party"]["cac:Contact"] = {
                "cbc:Name": d.simpleBuyerTradeParty.altname1
            };
        }
        //console.log('invoice data for ZugFerd', invoice);`);
