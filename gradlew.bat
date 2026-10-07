@echo off
setlocal

set "GRADLE_VERSION=9.6.0"
set "GRADLE_HOME=%~dp0.gradle-local\gradle-%GRADLE_VERSION%"
set "GRADLE_ZIP=%~dp0.gradle-local\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if exist "%GRADLE_HOME%\bin\gradle.bat" goto run_gradle

echo Gradle %GRADLE_VERSION% is not installed locally.
echo Downloading Gradle %GRADLE_VERSION%...
if not exist "%~dp0.gradle-local" mkdir "%~dp0.gradle-local"

powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%GRADLE_URL%' -OutFile '%GRADLE_ZIP%'"
if errorlevel 1 (
  echo Failed to download Gradle %GRADLE_VERSION%.
  exit /b 1
)

echo Extracting Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%~dp0.gradle-local' -Force"
if errorlevel 1 (
  echo Failed to extract Gradle %GRADLE_VERSION%.
  exit /b 1
)

del /q "%GRADLE_ZIP%" >nul 2>&1

:run_gradle
call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
