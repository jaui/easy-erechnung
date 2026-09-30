plugins {
    id("java")
    id("application")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

group = "de.aronhomberg"
version = "1.0-SNAPSHOT"

// Java 17 on Windows defaults to Cp1252; all invoice data is UTF-8.
// System proxy settings are used for the (optional, user-triggered) validator downloads.
val jvmDefaults = listOf(
    "-Dfile.encoding=UTF-8",
    "-Djava.net.useSystemProxies=true",
    "-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory",
)

application {
    mainClass = "de.aronhomberg.ERechnungApp"
    applicationDefaultJvmArgs = jvmDefaults
}

/** Optional switches: -Pkosit -Pverapdf -Pxrechnung -PabweichungErlauben (Excel/JSON + PDF: publish despite mismatch) */
fun switches() = listOf("kosit", "verapdf", "xrechnung", "abweichungErlauben")
    .filter { project.hasProperty(it) }.map { "--$it" }

tasks.withType<JavaExec> {
    jvmArgs(jvmDefaults)
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.e-iceblue.com/nexus/content/groups/public/")
    }
}

dependencies {
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("e-iceblue:spire.pdf.free:9.13.0")
    implementation("org.mustangproject:validator:2.15.0:shaded")
    implementation("org.verapdf:verapdf-pdfbox-validation:1.26.2")
    implementation("com.openai:openai-java:0.11.0")
    implementation("org.apache.pdfbox:pdfbox-tools:3.0.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.mustangproject:library:2.15.0")
    implementation("org.apache.pdfbox:pdfbox-debugger:3.0.2")
    implementation("org.apache.pdfbox:preflight:3.0.2")
    implementation("org.swinglabs:swingx:1.6.1")
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("buildERechnung") {
    group = "application"
    description = "Create Factur-X/ZUGFeRD EN16931 PDF + external XML and validate with Mustang"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("de.aronhomberg.BuildZugferdInvoice")
    args(project.findProperty("outDir")?.toString() ?: "rechnungen.out/erechnung")
}

tasks.register<JavaExec>("convertRechnungen") {
    group = "application"
    description = "Convert rechnungen.in (+ verified JSON) to validated ZUGFeRD under rechnungen.out"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("de.aronhomberg.ConvertRechnungenToZugferd")
    args(listOf(
        project.findProperty("inDir")?.toString() ?: "rechnungen.in",
        project.findProperty("outDir")?.toString() ?: "rechnungen.out",
        project.findProperty("jsonDir")?.toString() ?: "rechnungen.out/verified",
    ) + switches())
}

tasks.register<JavaExec>("excelRechnungen") {
    group = "application"
    description = "Create validated ZUGFeRD EN16931 invoices (own PDF layout) from an Excel workbook"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("de.aronhomberg.ExcelRechnungenToZugferd")
    args(listOfNotNull(
        project.findProperty("excel")?.toString() ?: "rechnungen.in/rechnungen.xlsx",
        project.findProperty("outDir")?.toString() ?: "rechnungen.out",
        project.findProperty("sheet")?.toString(),
    ) + switches())
}

tasks.register<JavaExec>("excelVorlage") {
    group = "application"
    description = "Write the Excel input template with fictitious sample data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("de.aronhomberg.ExcelTemplateWriter")
    args(project.findProperty("outFile")?.toString() ?: "templates/excel/rechnungen-vorlage.xlsx")
}

// cmd.exe limits a line to 8191 characters; the full jar list exceeds it -> use a wildcard classpath
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        windowsScript.writeText(
            windowsScript.readLines().joinToString("\r\n") {
                if (it.startsWith("set CLASSPATH=")) "set CLASSPATH=%APP_HOME%\\lib\\*" else it
            }
        )
    }
}

tasks.register<JavaExec>("pruefprogrammeLaden") {
    group = "application"
    description = "Download KoSIT validator (+ XRechnung configuration) and veraPDF [-Ptools=kosit,verapdf,mustang]"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("de.aronhomberg.ToolDownloader")
    args(project.findProperty("tools")?.toString() ?: "kosit,verapdf")
}
