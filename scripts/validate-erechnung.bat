@echo off
REM Validate a ZUGFeRD/Factur-X PDF or CII XML with official Mustang-CLI (Maven Central).
REM Usage: validate-erechnung.bat path\to\file.pdf|xml
setlocal
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
set "MUSTANG=C:\localapps\mustang\Mustang-CLI-2.26.0.jar"
if "%~1"=="" (
  echo Usage: %~nx0 ^<pdf-or-xml^>
  exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -jar "%MUSTANG%" --action validate --source "%~1"
