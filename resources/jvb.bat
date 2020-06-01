@echo off

if "%1"=="" goto usage
if "%1"=="/h" goto usage
if "%1"=="/?" goto usage
goto :begin

:usage
echo Usage:
echo %0 [OPTIONS], where options can be:
echo    --apis=APIS where APIS is a comma separated list of APIs to enable. Currently the only supported API is rest. The default is none.
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

java -Djava.util.logging.config.file=lib/logging.properties -Djdk.tls.ephemeralDHKeySize=2048 -cp %cp% %mainClass% %*
