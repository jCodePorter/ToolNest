@echo off
REM ============================================================
REM  ToolNest package script
REM
REM  Steps: compile fat jar -> copy into build\input
REM         -> jpackage app-image
REM  Output: build\dist\ToolNest\ToolNest.exe  (double-click to run)
REM
REM  NOTE: keep this file ASCII-only. cmd.exe reads .cmd files
REM  using the system ANSI codepage (GBK on Chinese Windows),
REM  so UTF-8 Chinese text here would corrupt the commands.
REM
REM  For a real installer (Start Menu shortcut, desktop icon)
REM  install WiX Toolset v3.14, change --type app-image to
REM  --type exe, and add: --win-menu --win-shortcut --win-dir-chooser
REM ============================================================
setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "MAVEN_HOME=D:\Dev\apache-maven-3.8.5"
set "APP_NAME=ToolNest"
set "APP_VERSION=1.0.0"
set "MAIN_JAR=toolnest.jar"
set "MAIN_CLASS=com.bytefuture.toolnest.Launcher"

cd /d "%~dp0"

echo [1/4] Building fat jar ...
call "%MAVEN_HOME%\bin\mvn.cmd" clean package
if errorlevel 1 (
  echo [ERROR] Maven build failed
  exit /b 1
)

echo [2/4] Preparing jpackage input ...
if not exist "build\input" mkdir "build\input"
if not exist "build\dist"  mkdir "build\dist"
copy /Y "target\%MAIN_JAR%" "build\input\" >nul
if errorlevel 1 (
  echo [ERROR] Failed to copy jar
  exit /b 1
)

echo [3/4] Running jpackage ...
set "ICON_ARG="
if exist "src\main\resources\icon\app.ico" set "ICON_ARG=--icon src\main\resources\icon\app.ico"

"%JAVA_HOME%\bin\jpackage.exe" ^
  --type app-image ^
  --name %APP_NAME% ^
  --input build\input ^
  --main-jar %MAIN_JAR% ^
  --main-class %MAIN_CLASS% ^
  --dest build\dist ^
  --app-version %APP_VERSION% ^
  --vendor ToolNest ^
  %ICON_ARG% ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Duser.language=zh" ^
  --java-options "-Xmx512m"
if errorlevel 1 (
  echo [ERROR] jpackage failed
  exit /b 1
)

echo [4/4] Done.
echo.
echo   Output folder: %~dp0build\dist\%APP_NAME%\
echo   Executable:    %~dp0build\dist\%APP_NAME%\%APP_NAME%.exe
echo.

endlocal
