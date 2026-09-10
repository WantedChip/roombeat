@echo off
if not defined JAVA_TOOL_OPTIONS (
    set "JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED"
) else (
    echo %JAVA_TOOL_OPTIONS% | findstr /i "enable-native-access" >nul || set "JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% --enable-native-access=ALL-UNNAMED"
)
set DIR=%~dp0
call "%DIR%android\gradlew.bat" -p "%DIR%android" %*

