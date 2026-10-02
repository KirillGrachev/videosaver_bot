@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Saver Bot launcher for Windows.
rem Keep this file pure ASCII: cmd.exe re-reads the batch after "chcp" and any
rem non-ASCII byte desyncs its parser (stray "is not recognized" errors).
rem The application itself prints UTF-8; chcp below only fixes the console.
chcp 65001 >nul 2>&1

set "HERE=%~dp0"
set "LAUNCHER=%HERE%saver-launcher.jar"
set "RJ=%HERE%runtime\java.txt"

if not exist "%LAUNCHER%" (
    echo.
    echo [start] ERROR: saver-launcher.jar was not found next to start.bat
    echo Looked here: %LAUNCHER%
    echo [start] Unpack the whole dist folder and keep start.bat beside the jars.
    goto :fail
)

rem 1) Reuse the JRE 21 downloaded during a previous run.
if not exist "%RJ%" goto :system_java
set /p JAVAEXE=<"%RJ%"
if not exist "!JAVAEXE!" goto :system_java
call :launch "!JAVAEXE!"
goto :done

:system_java
rem 2) Any Java on PATH works; the launcher upgrades itself to JRE 21 if needed.
where java >nul 2>&1
if errorlevel 1 goto :download_java
call :launch java
goto :done

:download_java
rem 3) No Java at all: fetch Temurin 21 JRE through PowerShell (uses the OS trust store).
echo.
echo [start] Java was not found on this machine.
echo [start] Downloading Temurin 21 JRE into runtime\ (one time, about 45 MB).
echo [start] Keep this window open until it finishes.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; $d='%HERE%runtime\'; New-Item -ItemType Directory -Force -Path $d | Out-Null; $z=$d+'jre21.zip'; Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse' -OutFile $z; Expand-Archive -LiteralPath $z -DestinationPath $d -Force; Remove-Item $z; $exe=(Get-ChildItem -Path $d -Recurse -Filter java.exe | Select-Object -First 1).FullName; Set-Content -Path ($d+'java.txt') -Value $exe"
if errorlevel 1 goto :fail
set /p JAVAEXE=<"%RJ%"
call :launch "!JAVAEXE!"
goto :done

:launch
rem The JDK's own startup notices are noise: JDK 22+ reports the native library
rem load of sqlite-jdbc as a restricted System::load call, JDK 23+ reports the
rem terminally deprecated sun.misc.Unsafe methods reached through Guava. Each
rem flag is added only where the JVM knows it: an unknown one stops the start.
set "FLAGS="
set "VER=0"
for /f "tokens=3" %%v in ('""%~1" -version 2^>^&1" ^| findstr /c:"version"') do set "VER=%%~v"
for /f "delims=. tokens=1" %%m in ("!VER!") do set "MAJOR=%%m"
if not defined MAJOR set "MAJOR=0"
if !MAJOR! geq 22 set "FLAGS=--enable-native-access=ALL-UNNAMED"
if !MAJOR! geq 23 set "FLAGS=!FLAGS! --sun-misc-unsafe-memory-access=allow"
"%~1" !FLAGS! -jar "%LAUNCHER%" %*
exit /b !errorlevel!

:done
set "CODE=!errorlevel!"
echo.
echo [start] Saver Bot stopped with exit code !CODE!.
echo [start] Press any key to close this window.
pause >nul
exit /b !CODE!

:fail
echo.
echo [start] Startup failed. Read the message above, then press any key.
pause >nul
exit /b 1

