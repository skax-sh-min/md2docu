@echo off
setlocal
set "SCRIPT=%~dp0release.sh"
call "%~dp0run_sh.cmd" %*
exit /b %ERRORLEVEL%
