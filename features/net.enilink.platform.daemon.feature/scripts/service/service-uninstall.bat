@echo off
echo Stopping and uninstalling service %SERVICE_NAME%...

call %~dp0%\setenv.bat

SC stop %SERVICE_NAME%
SC delete %SERVICE_NAME%
