package de.aronhomberg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mustangproject.BankDetails;
import org.mustangproject.Contact;
import org.mustangproject.Invoice;
import org.mustangproject.Item;
import org.mustangproject.Product;
import org.mustangproject.TradeParty;
import org.mustangproject.ZUGFeRD.Profiles;
import org.mustangproject.ZUGFeRD.TransactionCalculator;
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider;
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3;
import org.mustangproject.validator.ZUGFeRDValidator;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Converts PDFs from {@code rechnungen.in} (+ extracted JSON) into validated
 * Factur-X / ZUGFeRD EN16931 PDFs with embedded and external XML under {@code rechnungen.out}.
 *
 * Usage:
 *   gradlew convertRechnungen
 *   gradlew convertRechnungen -PinDir=rechnungen.in -PoutDir=rechnungen.out -PjsonDir=rechnungen.out/verified
 */
public final class ConvertRechnungenToZugferd {
    private ConvertRechnungenToZugferd() {}

    private static final String EXEMPTION_REASON =
            "Kleinunternehmer gemäß § 19 UStG – es wird keine Umsatzsteuer berechnet.";
    private static final String VISIBLE_19_NOTE =
            "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet (Kleinunternehmerregelung).";
    private static final String[] MONTHS_DE = {
            "Januar", "Februar", "März", "April", "Mai", "Juni",
            "Juli", "August", "September", "Oktober", "November", "Dezember"};
    private static final DateTimeFormatter DATE_DE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path inDir = Path.of(args.length > 0 ? args[0] : "rechnungen.in");
        Path outDir = Path.of(args.length > 1 ? args[1] : "rechnungen.out");
        // Only manually verified JSON is used; raw OCR/LLM output must be checked and copied there first.
        Path jsonDir = Path.of(args.length > 2 ? args[2] : "rechnungen.out/verified");

        if (!Files.isDirectory(inDir)) {
            System.err.println("Input folder missing: " + inDir.toAbsolutePath());
            System.exit(1);
        }
        Files.createDirectories(outDir);

        List<Path> pdfs;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inDir, "*.pdf")) {
            pdfs = StreamSupport.stream(stream.spliterator(), false)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (pdfs.isEmpty()) {
            System.err.println("No PDFs in " + inDir.toAbsolutePath());
            System.exit(1);
        }

        int failed = 0;
        for (Path pdf : pdfs) {
            String stem = stripExt(pdf.getFileName().toString());
            Path jsonPath = jsonDir.resolve(stem + ".json");
            System.out.println("\n======== " + pdf.getFileName() + " ========");
            try {
                if (!Files.isRegularFile(jsonPath)) {
                    throw new IOException("Missing verified invoice JSON: " + jsonPath);
                }
                System.out.println("JSON: " + jsonPath);
                InvoiceResponse response = MAPPER.readValue(jsonPath.toFile(), InvoiceResponse.class);
                if (response == null || response.invoice == null) {
                    throw new IOException("JSON has no Invoice object: " + jsonPath);
                }
                Invoice mustang = toMustangInvoice(response.invoice, stem);
                Path invoiceOut = outDir.resolve(stem);
                Files.createDirectories(invoiceOut);

                Path originalCopy = invoiceOut.resolve(stem + "-original.pdf");
                Path pdfaPdf = invoiceOut.resolve(stem + "-unicode-fixed.pdf");
                Path zugferdPdf = invoiceOut.resolve(stem + "-zugferd.pdf");
                Path externalXml = invoiceOut.resolve(stem + "-factur-x.xml");

                Files.copy(pdf, originalCopy, StandardCopyOption.REPLACE_EXISTING);
                // Keep optical identity: only repair missing ToUnicode entries (Word ligatures), no re-rendering.
                // (Spire's PDF/A conversion replaced ligature glyphs, e.g. "Bitte" rendered as "Biae".)
                PdfToUnicodeFixer.fix(originalCopy, pdfaPdf);
                if (allExempt(response.invoice)) {
                    // §19 UStG / § 34a UStDV: the note must also be visible, not only in the XML (BT-120)
                    Path stamped = invoiceOut.resolve(stem + "-prepared.pdf");
                    if (PdfNoteStamper.stamp(pdfaPdf, stamped, VISIBLE_19_NOTE, "§ 19", "§19")) {
                        System.out.println("Added visible note: " + VISIBLE_19_NOTE);
                    }
                    pdfaPdf = stamped;
                }

                byte[] xml = generateXml(mustang);
                Files.write(externalXml, xml);
                Files.write(invoiceOut.resolve(stem + "-zugferd-invoice.xml"), xml);

                embedZugferd(pdfaPdf, zugferdPdf, mustang);

                boolean pdfOk = validate(zugferdPdf, invoiceOut.resolve(stem + "-mustang-pdf-report.xml"));
                boolean xmlOk = validate(externalXml, invoiceOut.resolve(stem + "-mustang-xml-report.xml"));
                System.out.println("PDF: " + zugferdPdf.toAbsolutePath());
                System.out.println("XML: " + externalXml.toAbsolutePath());
                System.out.println("Mustang PDF: " + (pdfOk ? "VALID" : "INVALID"));
                System.out.println("Mustang XML: " + (xmlOk ? "VALID" : "INVALID"));
                if (!pdfOk || !xmlOk) failed++;
            } catch (Exception e) {
                failed++;
                System.err.println("FAILED: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        System.out.println("\nDone. Failed: " + failed + " / " + pdfs.size());
        if (failed > 0) System.exit(1);
    }

    static Invoice toMustangInvoice(InvoiceResponse.Invoice src, String stem) {
        normalize(src, stem);

        LocalDate issue = LocalDate.parse(src.InvoiceDate);
        LocalDate due = (src.DueDate == null || src.DueDate.isBlank())
                ? issue.plusDays(14)
                : LocalDate.parse(src.DueDate);

        String taxId = safe(src.Seller.TaxIdentificationNumber);
        String vatId = safe(src.Seller.TaxVATNumber);
        if (taxId.isBlank() && vatId.isBlank()) {
            throw new IllegalArgumentException("Seller needs a tax number or VAT ID (§14 Abs. 4 Nr. 2 UStG)");
        }
        String iban = digitsOnlyIban(firstNonBlank(src.IBAN,
                src.PaymentMeans != null && src.PaymentMeans.PaymentInformation != null
                        ? src.PaymentMeans.PaymentInformation.IBAN : null));
        String bic = firstNonBlank(src.BIC,
                src.PaymentMeans != null && src.PaymentMeans.PaymentInformation != null
                        ? src.PaymentMeans.PaymentInformation.BIC : null);
        String accountName = firstNonBlank(src.PaymentReceiver, src.Seller.Name);

        TradeParty seller = new TradeParty(
                src.Seller.Name,
                src.Seller.StreetName,
                src.Seller.PostalCode,
                src.Seller.City,
                country(src.Seller.CountryCode)
        );
        if (!taxId.isBlank()) seller.addTaxID(taxId); // BT-32 (FC)
        if (!vatId.isBlank()) {
            seller.addVATID(vatId); // BT-31 (VA)
        } else {
            seller.setID(taxId); // BT-29: BR-CO-26 needs BT-29, BT-30 or BT-31
        }

        if (!iban.isBlank()) {
            BankDetails bank = (bic == null) ? new BankDetails(iban) : new BankDetails(iban, bic);
            if (accountName != null && !accountName.isBlank()) {
                bank.setAccountName(accountName);
            }
            seller.addBankDetails(bank);
        }

        TradeParty buyer = new TradeParty(
                src.Buyer.Name,
                src.Buyer.StreetName,
                src.Buyer.PostalCode,
                src.Buyer.City,
                country(src.Buyer.CountryCode)
        );
        // BG-6 / BG-9 contacts: only the name is known from the paper invoice (no phone/e-mail invented)
        Contact sellerContact = new Contact().setName(firstNonBlank(src.Seller.ContactName, src.Seller.Name));
        if (src.Seller.Email != null && !src.Seller.Email.isBlank()) {
            seller.setEmail(src.Seller.Email.trim()); // BT-34 electronic address
            sellerContact.setEMail(src.Seller.Email.trim()); // BT-43
        }
        if (src.Seller.Phone != null && !src.Seller.Phone.isBlank()) {
            sellerContact.setPhone(src.Seller.Phone.trim()); // BT-42
        }
        seller.setContact(sellerContact);
        if (src.Buyer.Email != null && !src.Buyer.Email.isBlank()) {
            buyer.setEmail(src.Buyer.Email.trim()); // BT-49 electronic address
        }
        if (src.Buyer.ContactName != null && !src.Buyer.ContactName.isBlank()) {
            buyer.setContact(new Contact().setName(src.Buyer.ContactName.trim()));
        }

        Invoice invoice = new Invoice()
                .setNumber(src.InvoiceNumber)
                .setIssueDate(toDate(issue))
                .setDueDate(toDate(due))
                .setCurrency(firstNonBlank(src.DocumentCurrencyCode, "EUR"))
                .setSender(seller)
                .setRecipient(buyer)
                .setPaymentTermDescription("Zahlbar innerhalb von "
                        + ChronoUnit.DAYS.between(issue, due) + " Tagen ohne Abzug.");

        if (src.PaymentReference != null && !src.PaymentReference.isBlank()) {
            invoice.setReferenceNumber(src.PaymentReference);
        }
        if (src.OrderReference != null && !src.OrderReference.isBlank()) {
            invoice.setBuyerOrderReferencedDocumentID(src.OrderReference.trim()); // BT-13
        }
        LocalDate periodStart = billingMonth(src.InvoiceNumber, issue);
        invoice.setDetailedDeliveryPeriod(toDate(periodStart),
                toDate(periodStart.withDayOfMonth(periodStart.lengthOfMonth())));

        int lineNo = 1;
        LocalDate lastServiceDate = null;
        Set<String> usedIds = new HashSet<>();
        for (InvoiceResponse.Invoice.InvoiceLine line : src.InvoiceLines) {
            if (line == null) continue;
            String name = cleanProductName(line.ProductName);
            String category = firstNonBlank(line.TaxCategoryCode, src.Tax.TaxCategoryCode);
            if (category == null) {
                throw new IllegalArgumentException("Line " + line.LineID + ": no VAT category (E for §19, S for standard rate)");
            }
            if ("S".equals(category) && line.TaxPercentage <= 0) {
                throw new IllegalArgumentException("Line " + line.LineID + ": category S with 0 % VAT is inconsistent");
            }
            LocalDate serviceDate = (line.ServiceDate == null || line.ServiceDate.isBlank())
                    ? null : LocalDate.parse(line.ServiceDate.trim());
            if (serviceDate != null) {
                name = serviceDate.format(DATE_DE) + " – " + name;
            }
            String unit = (line.Unit == null || line.Unit.isBlank()) ? "C62" : line.Unit.trim();
            Product product = new Product(name, "", unit, BigDecimal.valueOf(line.TaxPercentage))
                    .setTaxCategoryCode(category);
            if ("E".equals(category)) {
                product.setTaxExemptionReason(firstNonBlank(src.Tax.TaxExemptionReason, EXEMPTION_REASON));
            }
            String id = (line.LineID != null && !line.LineID.isBlank()) ? line.LineID.trim() : String.valueOf(lineNo);
            while (!usedIds.add(id)) id = id + "-" + lineNo;
            Item item = new Item(product,
                    BigDecimal.valueOf(line.UnitPrice),
                    BigDecimal.valueOf(line.Quantity)).setId(id);
            if (serviceDate != null) {
                item.setDetailedDeliveryPeriod(toDate(serviceDate), toDate(serviceDate));
            }
            invoice.addItem(item);
            if (serviceDate != null && (lastServiceDate == null || serviceDate.isAfter(lastServiceDate))) {
                lastServiceDate = serviceDate;
            }
            lineNo++;
        }
        // BT-72: last actual service date (avoids an empty ApplicableHeaderTradeDelivery, PEPPOL-EN16931-R008)
        invoice.setDeliveryDate(toDate(lastServiceDate != null
                ? lastServiceDate
                : periodStart.withDayOfMonth(periodStart.lengthOfMonth())));
        checkPayable(src, invoice);
        return invoice;
    }

    /** Fails unless Mustang's calculated amount due equals the total printed on the source invoice. */
    private static void checkPayable(InvoiceResponse.Invoice src, Invoice invoice) {
        if (src.MonetarySummation == null || src.MonetarySummation.PayableAmount <= 0) {
            throw new IllegalArgumentException("MonetarySummation.PayableAmount (printed total) is missing");
        }
        BigDecimal calculated = new TransactionCalculator(invoice).getDuePayable().setScale(2, RoundingMode.HALF_UP);
        BigDecimal expected = BigDecimal.valueOf(src.MonetarySummation.PayableAmount).setScale(2, RoundingMode.HALF_UP);
        if (calculated.compareTo(expected) != 0) {
            throw new IllegalStateException("Calculated amount due " + calculated.toPlainString()
                    + " != printed total " + expected.toPlainString());
        }
    }

    private static boolean allExempt(InvoiceResponse.Invoice src) {
        String fallback = src.Tax == null ? null : src.Tax.TaxCategoryCode;
        return src.InvoiceLines.stream().filter(l -> l != null)
                .allMatch(l -> "E".equals(firstNonBlank(l.TaxCategoryCode, fallback)));
    }

    /** First day of the billed month: from an invoice number like "August/2026", else the issue month. */
    private static LocalDate billingMonth(String invoiceNumber, LocalDate issue) {
        if (invoiceNumber != null) {
            Matcher m = Pattern.compile("(\\p{L}+)\\s*/\\s*(20\\d{2})").matcher(invoiceNumber);
            if (m.find()) {
                for (int i = 0; i < MONTHS_DE.length; i++) {
                    if (MONTHS_DE[i].equalsIgnoreCase(m.group(1))) {
                        return LocalDate.of(Integer.parseInt(m.group(2)), i + 1, 1);
                    }
                }
            }
        }
        return issue.withDayOfMonth(1);
    }

    static void normalize(InvoiceResponse.Invoice src, String stem) {
        if (src.Seller == null) src.Seller = new InvoiceResponse.Invoice.Party();
        if (src.Buyer == null) src.Buyer = new InvoiceResponse.Invoice.Party();
        if (src.InvoiceLines == null) src.InvoiceLines = new ArrayList<>();

        src.Seller.CountryCode = country(src.Seller.CountryCode);
        src.Buyer.CountryCode = country(src.Buyer.CountryCode);
        if (src.DocumentCurrencyCode == null || src.DocumentCurrencyCode.isBlank()) {
            src.DocumentCurrencyCode = "EUR";
        }

        // Legally relevant data is never invented: missing values abort instead of being guessed.
        if (src.InvoiceDate == null || src.InvoiceDate.isBlank()) {
            throw new IllegalArgumentException("InvoiceDate is missing");
        }
        LocalDate issue = LocalDate.parse(src.InvoiceDate);
        if (src.DueDate == null || src.DueDate.isBlank()) {
            src.DueDate = issue.plusDays(14).toString();
        }
        if (src.InvoiceNumber == null || src.InvoiceNumber.isBlank()
                || src.InvoiceNumber.toUpperCase(Locale.ROOT).startsWith("PO ")) {
            throw new IllegalArgumentException("InvoiceNumber is missing or looks like an order number: " + src.InvoiceNumber);
        }
        if (src.Tax == null) src.Tax = new InvoiceResponse.Invoice.Tax();

        for (InvoiceResponse.Invoice.InvoiceLine line : src.InvoiceLines) {
            if (line == null) continue;
            if (line.Unit == null || line.Unit.isBlank()) line.Unit = "C62";
        }
    }

    private static byte[] generateXml(Invoice invoice) {
        ZUGFeRD2PullProvider provider = new ZUGFeRD2PullProvider();
        provider.setProfile(Profiles.getByName("EN16931"));
        provider.generateXML(invoice);
        return provider.getXML();
    }

    private static void embedZugferd(Path pdfaPdf, Path outPdf, Invoice invoice) throws IOException {
        // FromA3 + ignorePDFAErrors turns the (non-PDF/A) Word PDF into PDF/A-3 by adding XMP and output intent;
        // conformance is verified afterwards by the Mustang validator (veraPDF).
        ZUGFeRDExporterFromA3 exporter = new ZUGFeRDExporterFromA3()
                .ignorePDFAErrors()
                .setProducer("easy-e-rechnung")
                .setCreator(System.getProperty("user.name", "easy-e-rechnung"))
                .setZUGFeRDVersion(2)
                .setProfile("EN16931")
                .load(pdfaPdf.toString());
        try {
            exporter.setTransaction(invoice);
            exporter.export(outPdf.toString());
        } finally {
            exporter.close();
        }
    }

    private static boolean validate(Path source, Path reportPath) throws IOException {
        ZUGFeRDValidator validator = new ZUGFeRDValidator();
        String report = validator.validate(source.toAbsolutePath().toString());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        int lastSummary = report.lastIndexOf("<summary");
        if (lastSummary >= 0) {
            String tail = report.substring(lastSummary, Math.min(report.length(), lastSummary + 80));
            if (tail.contains("status=\"valid\"")) return true;
            if (tail.contains("status=\"invalid\"")) return false;
        }
        return report.contains("status=\"valid\"");
    }

    private static String cleanProductName(String name) {
        if (name == null) return "Position";
        String n = name.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? "Position" : n;
    }

    private static String country(String code) {
        if (code == null || code.isBlank()) return "DE";
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String digitsOnlyIban(String iban) {
        if (iban == null) return "";
        return iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
