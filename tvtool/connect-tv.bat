@echo off
REM Double-click to reconnect the laptop to the Android TV over Wi-Fi.
REM The TV must be switched on and awake. Both devices stay on the same
REM Wi-Fi (here: O2 HB3-6742-EH); ADB connects to the TV IP, not the SSID.
title Mit Fernseher verbinden
echo Verbinde mit dem Fernseher 192.168.1.70 ...
py "%USERPROFILE%\Desktop\tvtool\tv.py" connect 192.168.1.70
echo.
echo Fertig. Fenster kann geschlossen werden.
pause
