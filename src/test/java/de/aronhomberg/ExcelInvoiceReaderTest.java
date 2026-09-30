package de.aronhomberg;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.LocaleUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workbooks are derived from the generated template (fictitious data). */
class ExcelInvoiceReaderTest {
    private static final String INVOICE_SHEET = "2026-09";

    @TempDir
    Path tmp;

    /** Template, modified by {@code change}, saved as a new file. */
    private Path workbook(String name, Consumer<Workbook> change) throws Exception {
        Path template = tmp.resolve("vorlage.xlsx");
        if (!Files.exists(template)) ExcelTemplateWriter.main(new String[]{template.toString()});
        Path out = tmp.resolve(name);
        try (InputStream in = Files.newInputStream(template); Workbook wb = new XSSFWorkbook(in)) {
            change.accept(wb);
            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
        return out;
    }

    private static InvoiceResponse.Invoice single(Path xlsx) throws Exception {
        List<ExcelInvoiceReader.ExcelInvoice> list = ExcelInvoiceReader.read(xlsx);
        assertEquals(1, list.size());
        assertNull(list.get(0).error(), list.get(0).error());
        return list.get(0).data();
    }

    private static Row row(Sheet sheet, int r) {
        Row row = sheet.getRow(r);
        return row != null ? row : sheet.createRow(r);
    }

    @Test
    void newHeaderBezeichnungWertIsRead() throws Exception {
        Path xlsx = workbook("neu.xlsx", wb -> { });
        try (InputStream in = Files.newInputStream(xlsx); Workbook wb = new XSSFWorkbook(in)) {
            Sheet setup = wb.getSheet(ExcelInvoiceReader.SETUP);
            assertEquals("Bezeichnung", setup.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Wert", setup.getRow(0).getCell(1).getStringCellValue());
        }
        InvoiceResponse.Invoice inv = single(xlsx);
        assertEquals("Max Mustermann", inv.Seller.Name);
        assertEquals("DE02120300000000202051", inv.IBAN);
        assertEquals("September/2026", inv.InvoiceNumber);
        assertEquals("Musterfirma GmbH", inv.Buyer.Name);
        assertEquals(4, inv.InvoiceLines.size());
    }

    @Test
    void oldHeaderNameWertStillReadsSellerName() throws Exception {
        Path xlsx = workbook("alt.xlsx", wb ->
                wb.getSheet(ExcelInvoiceReader.SETUP).getRow(0).getCell(0).setCellValue("Name"));
        InvoiceResponse.Invoice inv = single(xlsx);
        assertEquals("Max Mustermann", inv.Seller.Name);
        assertEquals("Max Mustermann", inv.PaymentReceiver);
    }

    @Test
    void duplicateSetupKeyFailsTheWholeRead() throws Exception {
        // the Setup block is read once for all invoices, so read() itself fails
        Path xlsx = workbook("doppelt.xlsx", wb -> {
            Sheet setup = wb.getSheet(ExcelInvoiceReader.SETUP);
            Row r = setup.createRow(setup.getLastRowNum() + 1);
            r.createCell(0).setCellValue("IBAN");
            r.createCell(1).setCellValue("DE89370400440532013000");
        });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExcelInvoiceReader.read(xlsx));
        assertTrue(e.getMessage().contains("doppelt"), e.getMessage());
        assertTrue(e.getMessage().contains("Setup"), e.getMessage());
        assertTrue(e.getMessage().contains("IBAN"), e.getMessage());
    }

    @Test
    void duplicateInvoiceKeyIsReportedForThatSheet() throws Exception {
        Path xlsx = workbook("doppelt-kunde.xlsx", wb -> {
            Sheet inv = wb.getSheet(INVOICE_SHEET);
            Row r = row(inv, 9); // empty row between control sum and item table
            r.createCell(0).setCellValue("Kunde");
            r.createCell(1).setCellValue("ANDERER");
        });
        List<ExcelInvoiceReader.ExcelInvoice> list = ExcelInvoiceReader.read(xlsx);
        assertEquals(1, list.size());
        assertNull(list.get(0).data());
        assertNotNull(list.get(0).error());
        assertTrue(list.get(0).error().contains("doppelt"), list.get(0).error());
        assertTrue(list.get(0).error().contains(INVOICE_SHEET), list.get(0).error());
    }

    @Test
    void duplicateWithSameValueIsAccepted() throws Exception {
        // e.g. a header row "Name | Max Mustermann" that was overwritten by mistake: not ambiguous
        Path xlsx = workbook("doppelt-gleich.xlsx", wb -> {
            Row header = wb.getSheet(ExcelInvoiceReader.SETUP).getRow(0);
            header.getCell(0).setCellValue("Name");
            header.getCell(1).setCellValue("Max Mustermann");
        });
        List<ExcelInvoiceReader.ExcelInvoice> list = ExcelInvoiceReader.read(xlsx);
        assertNull(list.get(0).error());
        assertEquals("Max Mustermann", list.get(0).data().Seller.Name);
    }

    @Test
    void dateAsDateCell() throws Exception {
        InvoiceResponse.Invoice inv = single(workbook("datum.xlsx", wb -> { }));
        assertEquals("2026-09-30", inv.InvoiceDate);
        assertEquals("2026-09-02", inv.InvoiceLines.get(0).ServiceDate);
        assertEquals("2026-09-01", inv.PeriodStart);
        assertEquals("2026-09-30", inv.PeriodEnd);
    }

    @Test
    void dateAsText() throws Exception {
        Path xlsx = workbook("datum-text.xlsx", wb -> {
            Sheet inv = wb.getSheet(INVOICE_SHEET);
            inv.getRow(1).getCell(1).setCellValue("02.09.2026"); // Rechnungsdatum
            inv.getRow(11).getCell(0).setCellValue("3.9.2026");  // date of the first item
        });
        InvoiceResponse.Invoice inv = single(xlsx);
        assertEquals("2026-09-02", inv.InvoiceDate);
        assertEquals("2026-09-03", inv.InvoiceLines.get(0).ServiceDate);
    }

    @Test
    void dateCellDoesNotDependOnTimeZone() throws Exception {
        Path xlsx = workbook("datum-tz.xlsx", wb -> { });
        // POI interprets date cells in its "user time zone"; a date must not shift by a day
        for (String zone : List.of("Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC")) {
            LocaleUtil.setUserTimeZone(TimeZone.getTimeZone(zone));
            try {
                assertEquals("2026-09-30", single(xlsx).InvoiceDate, zone);
            } finally {
                LocaleUtil.resetUserTimeZone();
            }
        }
    }
}
