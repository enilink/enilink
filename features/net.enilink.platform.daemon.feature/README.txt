
Windows Service to start Eclipse launchers.
Needs the poweroff plugin, due to sending "poweroff" and "y" to the eclipse console.

Usage: put into directory next to the launcher, name it like the Launcher, but insert "-svc" before ".exe".
Example: launcher is named "launcher.exe", then name the service "launcher-svc.exe".
 Throughout the rest of the document, ${launcher} will denote the launcher name you use.

IMPORTANT:
DO NOT use the options -console, -consoleLog or -debug in ${launcher}.ini!
REPLACE
 -console
with
 -Dosgi.console
 -Dosgi.console.enable.builtin=false
REPLACE
 -consoleLog
with
 -Declipse.consoleLog=true
REPLACE
 -debug
with


You can then install the service and start/stop it, it will create a log file ${launcher}-svc.log with the application output.

You can also test it with --foreground, in which case it
 will not register itself as a service and
 will not detach itself, printing the output on the console you start it from.
