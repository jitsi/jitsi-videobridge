@echo off

if "%1"=="" goto usage
if "%1"=="/h" goto usage
if "%1"=="/?" goto usage
goto :begin

:usage
echo Usage:
echo %0 secret [port] [host] [minPort] [maxPort]
echo.
echo Default values are:
echo.
exit /B 1

:begin

:: needed to overcome weird loop behavior in conjunction with variable expansion
SETLOCAL enabledelayedexpansion

set secret=%1

if "%2"=="" (
    set port=5275
) else (
    set port=%2
)

if "%3"=="" (
    set host=localhost
) else (
    set host=%3
)

if "%4"=="" (
    set minPort=10000
) else (
    set minPort=%4
)

if "%5"=="" (
    set maxPort=20000
) else (
    set maxPort=%5
)

set mainClass=org.jitsi.videobridge.Main

set cp=jitsi-videobridge.jar

FOR %%F IN (lib/*.jar) DO (
  SET cp=!cp!;lib/%%F%
)

java -Djava.library.path=lib/native/windows -cp %cp% %mainClass% --secret=%secret% --port=%port% --host=%host% --min-port=%minPort% --max-port=%maxPort%
