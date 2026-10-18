@echo off
setlocal
set "ROOT=%~dp0"

REM Start Minecraft 1.8.8 server in background
start /b "" cmd /c "cd /d %ROOT%server && java -jar server.jar --nogui"

if not exist "%ROOT%mwc-proxy\env.js" (
  copy /Y "%ROOT%eagler-mwc-integration\config\mwc-proxy.env.js" "%ROOT%mwc-proxy\env.js" >nul
)

REM Start MWC proxy in background
start /b "" cmd /c "cd /d %ROOT%mwc-proxy && set PORT=2344 && set DISABLE_SIGNAL=1 && bun dist/app.js"

REM Run Eaglercraft client in foreground
cd /d "%ROOT%eaglercraft"
call gradlew target_lwjgl_desktop:eaglercraftDebugRuntime

echo.
echo Client exited. Press any key to close...
pause >nul
endlocal
