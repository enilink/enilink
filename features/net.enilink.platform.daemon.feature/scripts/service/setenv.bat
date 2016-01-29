@echo off

set SERVICE_NAME=enilink-platform

set APP_DIR=%~dp0
if #%APP_DIR:~-1%# == #\# set APP_DIR=%APP_DIR:~0,-1%
set APP_DIR=%APP_DIR%\..

set ENV_EXTENSION=%APP_DIR%\setenv.bat
if exist %ENV_EXTENSION% call %ENV_EXTENSION%

:: Show admin login prompt
:-------------------------------------
REM  --> Check for permissions
>nul 2>&1 "%SYSTEMROOT%\system32\cacls.exe" "%SYSTEMROOT%\system32\config\system"

REM --> If error flag set, we do not have admin.
if '%errorlevel%' NEQ '0' (
    goto UACPrompt
) else ( goto gotAdmin )

:UACPrompt
    echo Set UAC = CreateObject^("Shell.Application"^) > "%temp%\getadmin.vbs"
    set params = %*:"=""
    echo UAC.ShellExecute "cmd.exe", "/c %~s0 %params%", "", "runas", 1 >> "%temp%\getadmin.vbs"

    "%temp%\getadmin.vbs"
    del "%temp%\getadmin.vbs"
    exit /B

:gotAdmin
:--------------------------------------
