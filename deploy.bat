@echo off
rem Runs deploy.ps1 from plain command line: deploy.bat install -release
if "%~1"=="" goto :help
if "%~1"=="-?" goto :help
if "%~1"=="/?" goto :help
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
exit /b %ERRORLEVEL%

:help
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" help
exit /b 1
