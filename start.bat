@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
set "PATH=C:\localapps\bun;C:\localapps\ollama;%JAVA_HOME%\bin;%PATH%"
set "EASY_ERECHNUNG_BUN=C:\localapps\bun\bun.exe"
set "OLLAMA_MODELS=C:\localapps\ollama\models"
REM Ensure Ollama is up (ignore error if already running)
start "" /B "C:\localapps\ollama\ollama.exe" serve >nul 2>&1
cd /d "C:\dev\easy-erechnung"
call "C:\localapps\easy-e-rechnung\bin\easy-e-rechnung.bat"
