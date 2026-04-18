@echo off
REM Deploy the freshly-built PrisonsMod jar into the Prism instance's mods folder.
REM Run this AFTER closing Minecraft (the old jar is file-locked while MC is open).

set MODS_DIR=%APPDATA%\PrismLauncher\instances\1.21.11\minecraft\mods
set BUILD_JAR=%~dp0build\libs\prisonsmod-0.1.0.jar

if not exist "%BUILD_JAR%" (
    echo [ERROR] Build jar not found at %BUILD_JAR%
    echo Run: gradlew build  first.
    pause
    exit /b 1
)

if not exist "%MODS_DIR%" (
    echo [ERROR] Prism mods folder not found: %MODS_DIR%
    pause
    exit /b 1
)

echo Removing old prisonsmod-*.jar from %MODS_DIR%...
del /f /q "%MODS_DIR%\prisonsmod-*.jar" 2>nul

echo Copying %BUILD_JAR% -> %MODS_DIR%
copy /y "%BUILD_JAR%" "%MODS_DIR%\" >nul
if errorlevel 1 (
    echo [ERROR] Copy failed. Is Minecraft still running?
    pause
    exit /b 1
)

echo [OK] Deployed. Launch Minecraft normally.
pause
