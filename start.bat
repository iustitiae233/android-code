@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Claude Remote
echo.
echo  Starting backend + Cloudflare tunnel ... (Ctrl-C to stop both)
echo.
node "%~dp0scripts\launch.mjs"
echo.
echo === launcher exited ===
pause
