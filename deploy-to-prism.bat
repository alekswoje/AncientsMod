@echo off
REM Deploy the freshly-built PrisonsMod jar into the Prism instance's mods folder.
REM Run this AFTER closing Minecraft (the old jar is file-locked while MC is open).
REM
REM Pauses at the end so a double-clicked window doesn't vanish before you can read
REM the result. For NON-INTERACTIVE use (an agent shell, a script, CI) the pause
REM would hang forever waiting for a keypress, so it is skipped when ANY of:
REM   * first arg is "nopause" (or "-y")   ->  deploy-to-prism.bat nopause
REM   * env var PRISMDEPLOY_NOPAUSE is set
REM   * env var CI is set
setlocal enableextensions

set "NOPAUSE="
if /i "%~1"=="nopause" set "NOPAUSE=1"
if /i "%~1"=="-y"      set "NOPAUSE=1"
if defined PRISMDEPLOY_NOPAUSE set "NOPAUSE=1"
if defined CI set "NOPAUSE=1"

set "MODS_DIR=%APPDATA%\PrismLauncher\instances\1.21.11\minecraft\mods"
set "BUILD_DIR=%~dp0build\libs"

REM Pick the newest non-sources prisonsmod jar in build/libs so we don't depend on
REM the version string staying in sync with this script. Older 0.x -> 0.1.0 churn
REM previously caused us to deploy a stale jar by hardcoding the file name.
set "BUILD_JAR="
for /f "delims=" %%f in ('dir /b /o-d "%BUILD_DIR%\prisonsmod-*.jar" 2^>nul ^| findstr /v "sources"') do (
    if not defined BUILD_JAR set "BUILD_JAR=%BUILD_DIR%\%%f"
)

if not defined BUILD_JAR (
    echo [ERROR] No prisonsmod-*.jar found in %BUILD_DIR%
    echo Run: gradlew build  first.
    goto :fail
)

if not exist "%BUILD_JAR%" (
    echo [ERROR] Build jar not found at %BUILD_JAR%
    echo Run: gradlew build  first.
    goto :fail
)

if not exist "%MODS_DIR%" (
    echo [ERROR] Prism mods folder not found: %MODS_DIR%
    goto :fail
)

echo Removing old prisonsmod-*.jar from %MODS_DIR%...
del /f /q "%MODS_DIR%\prisonsmod-*.jar" 2>nul
if exist "%MODS_DIR%\prisonsmod-*.jar" (
    echo [ERROR] Old jar still present after delete -- Minecraft is holding a file lock.
    echo Close Minecraft and re-run this script.
    goto :fail
)

echo Copying %BUILD_JAR% -^> %MODS_DIR%
copy /y "%BUILD_JAR%" "%MODS_DIR%\" >nul
if errorlevel 1 (
    echo [ERROR] Copy failed. Is Minecraft still running?
    goto :fail
)

echo [OK] Deployed %BUILD_JAR%. Launch Minecraft normally.
call :maybe_pause
exit /b 0

:fail
call :maybe_pause
exit /b 1

:maybe_pause
if not defined NOPAUSE pause
goto :eof
