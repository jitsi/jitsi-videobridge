@echo off

if "%1"=="" goto usage
if "%1"=="/h" goto usage
if "%1"=="/?" goto usage
goto :begin

:usage
echo Usage:
echo %0 [OPTIONS], where options can be:
echo	--secret=SECRET	sets the shared secret used to authenticate to the XMPP server
echo 	--domain=DOMAIN	sets the XMPP domain (default: host, if host is set, none otherwise)
echo 	--min-port=MP	sets the min port used for media (default: 10000)
echo 	--max-port=MP	sets the max port used for media (default: 20000)
echo 	--host=HOST	sets the hostname of the XMPP server (default: localhost)
echo 	--port=PORT	sets the port of the XMPP server (default: 5275)
echo.
exit /B 1

:begin

:: needed to overcome weird loop behavior in conjunction with variable expansion
SETLOCAL enabledelayedexpansion

set mainClass=org.jitsi.videobridge.Main
set cp=jitsi-videobridge.jar
FOR %%F IN (lib/*.jar) DO (
  SET cp=!cp!;lib/%%F%
)

java -Djava.library.path=lib/native/windows-64 -cp %cp% %mainClass% %*
