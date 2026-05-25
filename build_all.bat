@echo off
setlocal EnableDelayedExpansion

REM ==============================================================================
REM SFTP Sync Multi-Platform Package Builder for Windows
REM ==============================================================================
REM OS: Native Windows Command Prompt (CMD) Compatible Build Script
REM ==============================================================================

REM Generate ESC character for premium ANSI terminal colors
for /F %%a in ('powershell -NoProfile -Command "[char]27"') do set "ESC=%%a"

REM Define Premium RGB Color Palette (Flat UI Theme)
set "RED=%ESC%[38;2;231;76;60m"
set "GREEN=%ESC%[38;2;46;204;113m"
set "YELLOW=%ESC%[38;2;241;196;15m"
set "BLUE=%ESC%[38;2;52;152;219m"
set "MAGENTA=%ESC%[38;2;155;89;182m"
set "CYAN=%ESC%[38;2;26;188;156m"
set "BOLD=%ESC%[1m"
set "NC=%ESC%[0m"

REM Clear Screen & Print Premium Banner
cls
echo %CYAN%%BOLD%======================================================================%NC%
echo %CYAN%%BOLD%     * SFTP Sync Multi-Platform Package Builder (Windows Dev) *%NC%
echo %CYAN%%BOLD%======================================================================%NC%
echo.

REM Define Output Directories
set "OUTPUT_DIR=build-outputs"
set "ANDROID_OUT=%OUTPUT_DIR%\android"
set "LINUX_OUT=%OUTPUT_DIR%\linux"
set "WINDOWS_OUT=%OUTPUT_DIR%\windows"

REM Create Output Folders
if not exist "%ANDROID_OUT%" mkdir "%ANDROID_OUT%"
if not exist "%LINUX_OUT%" mkdir "%LINUX_OUT%"
if not exist "%WINDOWS_OUT%" mkdir "%WINDOWS_OUT%"

REM 1. OS and Build Environment Diagnostics
echo %BLUE%%BOLD%[1/4] OS and Build Environment Diagnostics%NC%
echo   - Detected Host OS : %BOLD%Windows (Native)%NC%
echo   - Packaging Tools Status:
echo     - rpmbuild   : %YELLOW%[-] SKIPPED%NC% (RPM builds are skipped on Windows host)
echo     - dpkg-deb   : %YELLOW%[-] SKIPPED%NC% (DEB builds are skipped on Windows host)
echo.

REM 2. Compile Android Package (APK)
echo %BLUE%%BOLD%[2/4] Compiling Android Package (.apk)%NC%
echo   - Executing: call gradlew.bat :composeApp:assembleDebug...
call gradlew.bat :composeApp:assembleDebug
if %ERRORLEVEL% equ 0 (
    set "APK_FILE="
    for /F "delims=" %%i in ('dir /b /s composeApp\build\outputs\apk\debug\*.apk 2^>nul') do set "APK_FILE=%%i"
    if defined APK_FILE (
        copy /Y "!APK_FILE!" "%ANDROID_OUT%\SftpSync-debug.apk" >nul
        echo   - %GREEN%%BOLD%[v] SUCCESS!%NC% Android package copied to: %BOLD%%ANDROID_OUT%\SftpSync-debug.apk%NC%
    ) else (
        echo   - %RED%%BOLD%[x] ERROR!%NC% Built APK not found in outputs!
    )
) else (
    echo   - %RED%%BOLD%[x] FAILED!%NC% Android compilation failed.
)
echo.

REM 3. Compile Linux Desktop Packages (RPM / DEB)
echo %BLUE%%BOLD%[3/4] Compiling Linux Desktop Packages (.rpm / .deb)%NC%
echo   - %YELLOW%%BOLD%[!] SKIPPED: RPM and DEB%NC% (Building native Linux packages requires a Linux host)
echo.

REM 4. Windows Desktop Packages (.msi / .exe)
echo %BLUE%%BOLD%[4/4] Windows Desktop Packages (.msi / .exe)%NC%
echo   - Executing: call gradlew.bat :composeApp:packageMsi :composeApp:packageExe...
call gradlew.bat :composeApp:packageMsi :composeApp:packageExe
if %ERRORLEVEL% equ 0 (
    set "MSI_FILE="
    for /F "delims=" %%i in ('dir /b /s composeApp\build\compose\binaries\main\msi\*.msi 2^>nul') do set "MSI_FILE=%%i"
    
    set "EXE_FILE="
    for /F "delims=" %%i in ('dir /b /s composeApp\build\compose\binaries\main\exe\*.exe 2^>nul') do set "EXE_FILE=%%i"
    
    set "COPIED=0"
    if defined MSI_FILE (
        copy /Y "!MSI_FILE!" "%WINDOWS_OUT%\" >nul
        set "COPIED=1"
    )
    if defined EXE_FILE (
        copy /Y "!EXE_FILE!" "%WINDOWS_OUT%\" >nul
        set "COPIED=1"
    )
    
    if !COPIED! equ 1 (
        echo   - %GREEN%%BOLD%[v] SUCCESS!%NC% Windows packages compiled and copied to: %BOLD%%WINDOWS_OUT%\%NC%
    ) else (
        echo   - %RED%%BOLD%[x] ERROR!%NC% Built Windows installer package not found!
    )
) else (
    echo   - %RED%%BOLD%[x] FAILED!%NC% Windows installer packaging failed.
    echo     %MAGENTA%TIP: Make sure you have the WiX Toolset installed and added to your PATH to build .msi packages on Windows.%NC%
)
echo.

REM Print Final Packaging Summary
echo %CYAN%%BOLD%======================================================================%NC%
echo %CYAN%%BOLD%                   * Multi-Platform Build Summary *%NC%
echo %CYAN%%BOLD%======================================================================%NC%
echo  All compiled distributions have been placed in the %BOLD%%OUTPUT_DIR%\%NC% directory:
echo.

REM List output directory files with PowerShell for beautiful formatting
if exist "%OUTPUT_DIR%" (
    powershell -NoProfile -Command ^
        "Get-ChildItem -Path '%OUTPUT_DIR%' -Recurse -File | ForEach-Object { " ^
        "  $name = $_.Name; " ^
        "  $size = [Math]::Round($_.Length/1MB, 2); " ^
        "  $rel = $_.FullName.Substring((Get-Item .).FullName.Length + 1); " ^
        "  Write-Host '  ' -NoNewline; " ^
        "  Write-Host '[v] ' -ForegroundColor Green -NoNewline; " ^
        "  Write-Host $name -ForegroundColor White -NoNewline; " ^
        "  Write-Host (' (Size: ' + $size + ' MB) -> [') -NoNewline; " ^
        "  Write-Host $rel -ForegroundColor Yellow -NoNewline; " ^
        "  Write-Host ']'; " ^
        "}"
)

echo.
echo %CYAN%%BOLD%======================================================================%NC%
echo   Have an absolutely beautiful day of multiplatform synchronization!
echo %CYAN%%BOLD%======================================================================%NC%
