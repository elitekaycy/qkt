goto :eof

:qktFindJava
call :qktTry "%QKT_JAVA_HOME%"
call :qktTry "%APP_HOME%\runtime"
call :qktTry "%JAVA_HOME%"
if defined QKT_JAVA goto :eof
for /f "delims=" %%j in ('where java.exe 2^>nul') do call :qktTry "%%~dpj.."
if defined QKT_JAVA goto :eof
for /d %%d in ("%USERPROFILE%\.jdks\*" "%USERPROFILE%\.gradle\jdks\*" "%ProgramFiles%\Java\*" "%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Microsoft\*" "%ProgramFiles%\Zulu\*" "%ProgramFiles%\Amazon Corretto\*") do call :qktTry "%%~d"
call :qktTry "%QKT_FALLBACK_JAVA_HOME%"
goto :eof

:qktTry
if defined QKT_JAVA goto :eof
if not exist "%~1\release" goto :eof
set QKT_MAJOR=
for /f "tokens=2 delims==" %%v in ('findstr /b "JAVA_VERSION=" "%~1\release"') do set "QKT_MAJOR=%%~v"
if not defined QKT_MAJOR goto :eof
for /f "tokens=1 delims=." %%m in ("%QKT_MAJOR%") do set "QKT_MAJOR=%%m"
if %QKT_MAJOR% geq 21 set "QKT_JAVA=%~f1"
goto :eof
