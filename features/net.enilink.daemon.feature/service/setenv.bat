@echo off

set APP_DIR=%~dp0
set ENV_EXTENSION=%APP_DIR%\setenv-override.bat

set SERVICE_NAME=enilink platform

if exist %ENV_EXTENSION% call %ENV_EXTENSION%
