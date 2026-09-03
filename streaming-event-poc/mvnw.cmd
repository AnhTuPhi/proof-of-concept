@REM Minimal Maven Wrapper for Windows. Downloads Maven 3.9.9 into
@REM %USERPROFILE%\.m2\wrapper\dists on first run, then invokes mvn.cmd.
@echo off
setlocal EnableDelayedExpansion

set "MVN_VERSION=3.9.9"
set "DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VERSION%/apache-maven-%MVN_VERSION%-bin.zip"
set "CACHE_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MVN_VERSION%-bin"
set "MVN_HOME=%CACHE_DIR%\apache-maven-%MVN_VERSION%"

if exist "%MVN_HOME%\bin\mvn.cmd" goto runmvn

echo [mvnw] Downloading Apache Maven %MVN_VERSION%...
if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%CACHE_DIR%\maven.zip' -UseBasicParsing"
if errorlevel 1 (
  echo [mvnw] Download failed.
  exit /b 1
)
echo [mvnw] Extracting...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -Path '%CACHE_DIR%\maven.zip' -DestinationPath '%CACHE_DIR%' -Force"
if errorlevel 1 (
  echo [mvnw] Extract failed.
  exit /b 1
)
del /q "%CACHE_DIR%\maven.zip"

:runmvn
call "%MVN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
