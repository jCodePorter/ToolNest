@echo off
REM ============================================================
REM  ToolNest build script
REM
REM  Forces Maven to run on JDK 21 (this machine's default
REM  JAVA_HOME points to JDK 8, which cannot compile JavaFX).
REM
REM  Usage:
REM    build.cmd              compile and package the fat jar
REM    build.cmd clean        clean the target directory
REM    build.cmd javafx:run   run the app directly
REM
REM  NOTE: keep this file ASCII-only. cmd.exe reads .cmd files
REM  using the system ANSI codepage (GBK on Chinese Windows),
REM  so UTF-8 Chinese text here would corrupt the commands.
REM ============================================================
setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "MAVEN_HOME=D:\Dev\apache-maven-3.8.5"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 21 not found at: %JAVA_HOME%
  exit /b 1
)
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo [ERROR] Maven not found at: %MAVEN_HOME%
  exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

if "%~1"=="" (
  call "%MAVEN_HOME%\bin\mvn.cmd" clean package
) else (
  call "%MAVEN_HOME%\bin\mvn.cmd" %*
)

endlocal
