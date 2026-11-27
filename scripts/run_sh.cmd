@echo off
setlocal EnableExtensions
set "BASH="
if not defined SCRIPT_BASH goto :find_git
if not exist "%SCRIPT_BASH%" goto :bad_bash
set "BASH=%SCRIPT_BASH%"
goto :found
:find_git
for /f "delims=" %%G in ('where git 2^>nul') do call :try "%%~dpG..\bin\bash.exe" & call :try "%%~dpG..\..\bin\bash.exe"
if defined BASH goto :found
call :try "%ProgramFiles%\Git\bin\bash.exe"
call :try "%ProgramFiles(x86)%\Git\bin\bash.exe"
call :try "%LocalAppData%\Programs\Git\bin\bash.exe"
if defined BASH goto :found
echo ERROR: Git for Windows bash not found. Set SCRIPT_BASH to bash.exe. 1>&2
exit /b 1
:bad_bash
echo ERROR: SCRIPT_BASH does not exist: %SCRIPT_BASH% 1>&2
exit /b 1
:try
if not defined BASH if exist "%~1" set "BASH=%~f1"
exit /b 0
:found
set "SCRIPT_BASH=%BASH%"
"%BASH%" "%SCRIPT%" %*
exit /b %ERRORLEVEL%
