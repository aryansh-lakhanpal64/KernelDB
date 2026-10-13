@echo off
setlocal

echo ===============================================
echo KernelDB - OS/DBMS Transaction & Concurrency Lab
echo ===============================================

set "KDB_JDK=%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
if exist "%KDB_JDK%\bin\java.exe" set "JAVA_HOME=%KDB_JDK%"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: Java was not found. Install JDK 21 and set JAVA_HOME.
  pause
  exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
  echo ERROR: Maven was not found. Install Maven 3.9+ and add its bin folder to PATH.
  pause
  exit /b 1
)

java -version
mvn -version
mvn -U clean javafx:run
if errorlevel 1 (
  echo.
  echo KernelDB did not start. Check the Maven/Java output above.
  pause
  exit /b 1
)
endlocal
