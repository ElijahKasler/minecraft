@echo off
setlocal
set "ROOT=%~dp0"

REM Start Minecraft 1.8.8 server in background (prefer bundled Temurin 8, fall back to PATH java)
if exist "%ROOT%tools\temurin8\bin\java.exe" (
  start /b "" cmd /c "cd /d %ROOT%server && "%ROOT%tools\temurin8\bin\java.exe" -Xms1G -Xmx1G -jar server.jar --nogui"
) else (
  start /b "" cmd /c "cd /d %ROOT%server && java -jar server.jar --nogui"
)

if not exist "%ROOT%mwc-proxy\env.js" (
  copy /Y "%ROOT%eagler-mwc-integration\config\mwc-proxy.env.js" "%ROOT%mwc-proxy\env.js" >nul
)

REM Start MWC proxy in background
start /b "" cmd /c "cd /d %ROOT%mwc-proxy && set PORT=2344 && set DISABLE_SIGNAL=1 && bun dist/app.js"

REM Start ViaProxy (protocol translation) if present, so the client can join
REM servers of other versions (1.7 to latest) via proxy://127.0.0.1:25568
if exist "%ROOT%tools\viaproxy\ViaProxy.jar" (
  start /b "" cmd /c "cd /d %ROOT%tools\viaproxy && set VP_RUN_DIR=%ROOT%tools\viaproxy && java -DskipUpdateCheck=1 -DignoreSystemRequirements=1 -jar ViaProxy.jar config viaproxy.yml"
)

REM Run Eaglercraft client in foreground
cd /d "%ROOT%eaglercraft"
call gradlew target_lwjgl_desktop:eaglercraftDebugRuntime

echo.
echo Client exited. Press any key to close...
pause >nul
endlocal
