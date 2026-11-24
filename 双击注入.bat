@echo off
setlocal
set "APP=%~dp0build-oss\AZclient-8.0.0-offline-oss.exe"
if not exist "%APP%" (
  echo 请先运行 build.bat，或按照 README.md 构建项目。
  pause
  exit /b 1
)
start "" "%APP%"
