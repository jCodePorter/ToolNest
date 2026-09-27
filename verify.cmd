@echo off
REM ============================================================
REM  ToolNest verify script  (development only)
REM
REM  Compiles and runs the development-time probes kept in
REM  verify\src\main\java.  These are NOT part of the product
REM  and deliberately live outside src\main so they never end up
REM  in the shipped fat jar.
REM
REM  Usage:
REM    verify.cmd compile        compile the probes only
REM    verify.cmd run <Class>    compile then run one probe
REM    verify.cmd cron           shortcut: run CronTest
REM    verify.cmd image          shortcut: run ImageTest
REM    verify.cmd qr             shortcut: run QrTest (no UI)
REM    verify.cmd qrpic          shortcut: run QrModuleProbe (UI)
REM    verify.cmd sel            shortcut: run SelectionE2EProbe (crop/selection)
REM    verify.cmd smoke          shortcut: run SmokeTest (screenshots)
REM    verify.cmd json           shortcut: run JsonProbe
REM    verify.cmd body           shortcut: run HttpBodyProbe
REM    verify.cmd nav            shortcut: run ContentPaneProbe
REM    verify.cmd preview        shortcut: run DesignPreview
REM
REM  Add -nobuild to skip rebuilding the fat jar (faster when you only
REM  changed probe code):  verify.cmd smoke -nobuild
REM
REM  How it works: the probes are ordinary classes that only use
REM  the application's public API, so we compile them against the
REM  packaged fat jar (target\toolnest.jar) and run them with that
REM  same jar on the classpath.  No extra Maven plugins required.
REM
REM  NOTE: keep this file ASCII-only. cmd.exe reads .cmd files
REM  using the system ANSI codepage (GBK on Chinese Windows),
REM  so UTF-8 Chinese text here would corrupt the commands.
REM ============================================================
setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "MAVEN_HOME=D:\Dev\apache-maven-3.8.5"
set "FAT_JAR=target\toolnest.jar"
set "VERIFY_SRC=verify\src\main\java"
set "VERIFY_OUT=target\verify-classes"

cd /d "%~dp0"

if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo [ERROR] JDK 21 not found at: %JAVA_HOME%
  exit /b 1
)

REM  The probes compile against the fat jar.  A stale jar means the probes
REM  link against old classes and fail with a confusing "cannot find symbol".
REM  So we rebuild the jar every run unless the caller passes -nobuild.
set "SKIP_BUILD=0"
if /I "%~2"=="-nobuild" set "SKIP_BUILD=1"
if /I "%~1"=="-nobuild" set "SKIP_BUILD=1"

if "%SKIP_BUILD%"=="0" (
  echo [1/3] Building fat jar ...
  call "%MAVEN_HOME%\bin\mvn.cmd" -q -o clean package -DskipTests
  if errorlevel 1 (
    echo [ERROR] Maven build failed
    exit /b 1
  )
) else (
  if not exist "%FAT_JAR%" (
    echo [ERROR] -nobuild given but %FAT_JAR% does not exist
    exit /b 1
  )
  echo [1/3] Skipping jar build ^(-nobuild^)
)

if /I "%~1"=="compile" goto :compile
if /I "%~1"==""       goto :usage

REM ---- map shortcuts to class names -------------------------
set "TARGET_CLASS=%~2"
if /I "%~1"=="cron"    set "TARGET_CLASS=com.bytefuture.toolnest.CronTest"
if /I "%~1"=="image"   set "TARGET_CLASS=com.bytefuture.toolnest.ImageTest"
if /I "%~1"=="qr"      set "TARGET_CLASS=com.bytefuture.toolnest.QrTest"
if /I "%~1"=="smoke"   set "TARGET_CLASS=com.bytefuture.toolnest.SmokeTestLauncher"
if /I "%~1"=="json"    set "TARGET_CLASS=com.bytefuture.toolnest.JsonProbeLauncher"
if /I "%~1"=="body"    set "TARGET_CLASS=com.bytefuture.toolnest.modules.http.HttpBodyProbeLauncher"
if /I "%~1"=="qrpic"   set "TARGET_CLASS=com.bytefuture.toolnest.modules.qrcode.QrModuleProbeLauncher"
if /I "%~1"=="sel"     set "TARGET_CLASS=com.bytefuture.toolnest.modules.qrcode.SelectionE2EProbeLauncher"
if /I "%~1"=="nav"     set "TARGET_CLASS=com.bytefuture.toolnest.ContentPaneProbeLauncher"
if /I "%~1"=="preview" set "TARGET_CLASS=com.bytefuture.toolnest.DesignPreview"
if /I "%~1"=="run"     set "TARGET_CLASS=%~2"

if "%TARGET_CLASS%"=="" (
  echo [ERROR] Unknown probe: %~1
  goto :usage
)

:compile
echo [2/3] Compiling probes from %VERIFY_SRC% ...
if not exist "%VERIFY_OUT%" mkdir "%VERIFY_OUT%"

REM  Collect every probe source file into a javac @argfile.
REM  Inside an argfile javac treats backslash as an escape character, so we
REM  must emit forward slashes (a Windows path with / works fine for javac).
REM  Forward slashes also avoid cmd quoting pitfalls in a long argument list.
set "ARGFILE=%VERIFY_OUT%\sources.txt"
if exist "%ARGFILE%" del "%ARGFILE%"
setlocal enabledelayedexpansion
for /r "%VERIFY_SRC%" %%F in (*.java) do (
  set "PROBE_PATH=%%F"
  set "PROBE_PATH=!PROBE_PATH:\=/!"
  echo !PROBE_PATH!>> "%ARGFILE%"
)
endlocal
for %%F in ("%ARGFILE%") do if %%~zF==0 (
  echo [ERROR] No probe sources found under %VERIFY_SRC%
  exit /b 1
)

REM  Note: MSYS2_ARG_CONV_EXCL is only needed when invoking javac from
REM  Git Bash.  Inside a .cmd file cmd.exe passes arguments through
REM  verbatim, so we simply call javac.exe directly.
"%JAVA_HOME%\bin\javac.exe" ^
  -encoding UTF-8 --release 21 ^
  -cp "%FAT_JAR%" ^
  -d "%VERIFY_OUT%" ^
  "@%ARGFILE%"
if errorlevel 1 (
  echo [ERROR] Probe compilation failed
  exit /b 1
)

if /I "%~1"=="compile" (
  echo [3/3] Done. Probe classes in %VERIFY_OUT%
  goto :eof
)

echo [3/3] Running %TARGET_CLASS% ...
"%JAVA_HOME%\bin\java.exe" ^
  -Dfile.encoding=UTF-8 ^
  -Duser.language=zh ^
  -cp "%FAT_JAR%;%VERIFY_OUT%" ^
  %TARGET_CLASS%
if errorlevel 1 (
  echo [ERROR] Probe run failed
  exit /b 1
)

goto :eof

:usage
echo Usage:
echo   verify.cmd compile        compile probes only
echo   verify.cmd run ^<Class^>    compile then run a probe class
echo   verify.cmd cron ^| image ^| qr ^| qrpic ^| sel ^| json ^| body ^| nav ^| preview ^| smoke
echo.
exit /b 1
