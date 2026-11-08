@echo off
title EasySubs Standalone Launcher
cd /d "%~dp0"
if exist "C:\Python315\pythonw.exe" (
    start "" "C:\Python315\pythonw.exe" "VLSub_App.py"
) else (
    start "" pyw "VLSub_App.py"
)
