@echo off

if "%1"=="" goto usage
if "%1"=="/h" goto usage
if "%1"=="/?" goto usage
goto :begin

:usage
echo Usage:
echo %0 [OPTIONS], where options can be:
echo	--secret=SECRET	sets the shared secret used to authenticate to the XMPP server
echo 	--domain=DOMAIN	sets the XMPP domain (default: none)
echo 	--host=HOST	sets the hostname of the XMPP server (default: domain, if domain is set, localhost otherwise)
echo 	--port=PORT	sets the port of the XMPP server (default: 5275)
echo    --subdomain=SUBDOMAIN sets the sub-domain used to bind JVB XMPP component (default: jitsi-videobridge)
echo    --apis=APIS where APIS is a comma separated list of APIs to enable. Currently supported APIs are xmpp and rest. The default is xmpp.
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

java -Djava.util.logging.config.file=lib/logging.properties -cp %cp% %mainClass% %*
