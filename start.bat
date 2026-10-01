@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
set "OLLAMA_DIR=%LOCALAPPDATA%\Programs\Ollama"
set "PATH=C:\localapps\bun;%OLLAMA_DIR%;%JAVA_HOME%\bin;%PATH%"
set "EASY_ERECHNUNG_BUN=C:\localapps\bun\bun.exe"
REM Ensure Ollama is up (models in %USERPROFILE%\.ollama; ignore error if already running)
start "" /B "%OLLAMA_DIR%\ollama.exe" serve >nul 2>&1
cd /d "C:\dev\easy-erechnung"
call "C:\localapps\easy-e-rechnung\bin\easy-e-rechnung.bat"
