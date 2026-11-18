@echo off
title PureGuard - One-Click Phone Installer
color 0B

echo ========================================================
echo      PureGuard - Direct Phone Installer
echo ========================================================
echo.

cd /d "%~dp0"
set "ADB=%~dp0platform-tools\adb.exe"
set "APK=%~dp0PureGuard-Sandbox-Debug.apk"

if not exist "%APK%" (
    color 0C
    echo [ERROR] PureGuard-Sandbox-Debug.apk not found in folder!
    pause
    exit /b 1
)

echo [1/4] Checking USB connection to phone...
"%ADB%" start-server >nul 2>&1

:WAIT_LOOP
"%ADB%" devices | findstr /R /C:"[	 ]device$" >nul
if %ERRORLEVEL% NEQ 0 (
    echo Waiting for you to approve "Allow USB Debugging" on the phone screen...
    timeout /t 3 /nobreak >nul
    goto WAIT_LOOP
)

echo.
echo [2/4] Phone connected! Removing old version and installing latest PureGuard APK...
"%ADB%" uninstall com.pureguard.filter >nul 2>&1
"%ADB%" install -r -t "%APK%"
if %ERRORLEVEL% NEQ 0 (
    color 0C
    echo.
    echo [ERROR] Installation failed. Freeing temporary app cache and retrying...
    "%ADB%" shell pm trim-caches 999999999999999999 >nul 2>&1
    "%ADB%" install -r -t "%APK%"
    if %ERRORLEVEL% NEQ 0 (
        echo [ERROR] Still failed. Check phone screen.
        pause
        exit /b 1
    )
)

echo.
echo [3/4] Enabling PureGuard Accessibility Filter Service automatically...
"%ADB%" shell settings put secure enabled_accessibility_services com.pureguard.filter/com.pureguard.filter.service.FilterAccessibilityService >nul 2>&1
"%ADB%" shell settings put secure accessibility_enabled 1 >nul 2>&1

echo [4/4] Launching PureGuard on your phone screen...
"%ADB%" shell am start -n com.pureguard.filter/.ui.MainActivity >nul 2>&1

color 0A
echo.
echo ========================================================
echo  SUCCESS! PureGuard is now installed and open on your phone!
echo ========================================================
echo.
pause
