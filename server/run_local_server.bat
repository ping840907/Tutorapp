@echo off
title Local LFM3-Audio-2B Inference Server
color 0B
echo ========================================================
echo        Local LFM3-Audio-2B Inference Server
echo ========================================================
echo.

echo [1/3] Checking Local IP Addresses...
for /f "tokens=4" %%a in ('route print ^| findstr 0.0.0.0 ^| findstr /v "0.0.0.0.*0.0.0.0"') do (
    set LOCAL_IP=%%a
)
echo.
echo -> Android Emulator Endpoint : http://10.0.2.2:8000/v1/audio/chat
echo -> Physical Phone Endpoint    : http://%LOCAL_IP%:8000/v1/audio/chat
echo.
echo ========================================================
echo [2/3] Checking Python dependencies...
python -m pip install -r requirements.txt --quiet --no-warn-script-location
echo.
echo [3/3] Starting LFM3-Audio Fast Server on port 8000...
python local_lfm3_server.py
pause
