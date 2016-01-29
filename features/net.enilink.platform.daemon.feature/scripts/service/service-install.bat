@echo off
echo Installing and Starting service %SERVICE_NAME%

call %~dp0%\setenv.bat

SC create %SERVICE_NAME% displayname= %SERVICE_NAME% binpath= %APP_DIR%\enilink-platform-svc.exe start= auto
SC start %SERVICE_NAME%
