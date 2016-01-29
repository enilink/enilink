@echo off
echo Stopping service %SERVICE_NAME%

call %~dp0%\setenv.bat

SC stop %SERVICE_NAME%
