@echo off
echo Starting service %SERVICE_NAME%

call %~dp0%\setenv.bat

SC start %SERVICE_NAME%
