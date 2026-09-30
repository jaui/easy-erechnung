# Meldungen an die Hersteller

Beide Punkte sind lokal durch `patch-rechnungfertig.ts` behoben (nach jedem App-Update erneut ausführen).

## 1. @e-invoice-eu/core 3.0.3 – BT-13 fehlt im Factur-X/ZUGFeRD-Profil EN16931

**Repository:** https://github.com/gflohr/e-invoice-eu

**Titel:** BT-13 (Purchase order reference) not written to CII for profile EN16931

**Beschreibung:**
In `dist/e-invoice-eu.cjs.js` (Mapping UBL → CII) wird `cac:OrderReference/cbc:ID` nach
`ram:ApplicableHeaderTradeAgreement/ram:BuyerOrderReferencedDocument/ram:IssuerAssignedID`
nur mit `fxProfileMask: FX_MASK_EXTENDED` übernommen. BT-13 ist jedoch Teil des
Kernmodells EN 16931 (und bereits ab Factur-X BASIC WL zulässig). Bei `ZUGFeRD-EN16931` /
`Factur-X-EN16931` fehlt die Bestellnummer deshalb im erzeugten CII, während sie in
`XRECHNUNG-UBL` enthalten ist.

**Erwartet:** `fxProfileMask` mindestens `FX_MASK_EN16931` (bzw. BASIC WL).

**Reproduktion:** UBL-Invoice mit `cac:OrderReference/cbc:ID` → `generate(…, { format: 'Factur-X-EN16931' })`
→ im CII fehlt `ram:BuyerOrderReferencedDocument`.

## 2. RechnungFertig 1.8.1 – Ansprechpartner des Käufers (BT-56) wird nicht übergeben

**Titel:** Käufer-Ansprechpartner fehlt in der E-Rechnung (BT-56)

**Beschreibung:**
Im Kundenstamm lässt sich ein Ansprechpartner nur über „Alternativer Name 1/2“ erfassen
(`altname1`, im Template z. B. „z. Hd. …“). In `createInvoiceData()` (`src/invoice.js`) wird für
`cac:AccountingCustomerParty/cac:Party` jedoch kein `cac:Contact/cbc:Name` erzeugt. Dadurch
steht der Ansprechpartner sichtbar im PDF, aber nicht im XML (BT-56 / `ram:DefinedTradeContact`),
d. h. PDF und XML weichen voneinander ab.

**Vorschlag:** eigenes Feld „Ansprechpartner“ (BT-56, optional Telefon BT-57 und E-Mail BT-58)
im Kundenstamm und Übergabe als `cac:Contact` des Käufers.

**Weitere Beobachtungen (niedrige Priorität):**
- Die Steuernummer des Verkäufers wird zusätzlich als BT-30 (`PartyLegalEntity/CompanyID`)
  ausgegeben; BT-30 ist für Registernummern (z. B. HRB) gedacht.
- `cac:InvoicePeriod/cbc:DescriptionCode` ist fest `3` (Rechnungsdatum); für Dienstleistungen
  wäre `35` (Leistungsdatum) passender bzw. konfigurierbar.
- Kein Verwendungszweck (BT-83 / `cbc:PaymentID`), obwohl die Vorlage zur Angabe der
  Rechnungsnummer auffordert.
