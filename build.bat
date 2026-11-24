@echo off
setlocal
set "ROOT=%~dp0"
if not exist "%ROOT%build-open-source.ps1" (
  echo build-open-source.ps1 not found: "%ROOT%build-open-source.ps1"
  exit /b 1
)
echo === AZclient Offline OSS build ===
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%build-open-source.ps1" %*
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
echo BUILD OK
exit /b 0
