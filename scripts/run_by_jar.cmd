@echo off
setlocal
set "SCRIPT=%~dp0run_by_jar.sh"
call "%~dp0run_sh.cmd" %*
exit /b %ERRORLEVEL%
