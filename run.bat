@echo off
chcp 65001 >nul
title 智课堂 · 大学课堂互动与学情分析系统（Spring Boot 版）
cd /d %~dp0

echo ============================================================
echo   智课堂 · 大学课堂互动与学情分析系统
echo   Spring Boot 3.2 + MyBatis + MySQL 8.0 + Thymeleaf
echo ============================================================
echo.

rem ---------------- 1. 定位 JDK 17 ----------------
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :jdk_ok
for %%p in (
    "D:\develop\Java\jdk-17"
    "C:\Program Files\Java\jdk-17"
) do if exist "%%~p\bin\java.exe" set "JAVA_HOME=%%~p"
for /d %%p in ("C:\Program Files\Eclipse Adoptium\*") do if exist "%%~p\bin\java.exe" set "JAVA_HOME=%%~p"
:jdk_ok
where java >nul 2>&1
if errorlevel 1 if not defined JAVA_HOME (
    echo [错误] 未找到 JDK 17：请安装 JDK 17 并配置 JAVA_HOME，或把便携版放到 D:\develop\Java\jdk-17
    pause & exit /b 1
)
rem 打印实际使用的 JDK，便于排查版本问题（本项目需 JDK 17+）
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
echo       JDK 路径：%JAVA_BIN%
"%JAVA_BIN%" -version 2>&1 | findstr /i "version"

rem ---------------- 2. 定位 Maven ----------------
where mvn >nul 2>&1
if not errorlevel 1 (
    set "MVN=mvn"
) else if exist "%~dp0runtime\apache-maven-3.9.6\bin\mvn.cmd" (
    set "MVN=%~dp0runtime\apache-maven-3.9.6\bin\mvn.cmd"
) else (
    echo [错误] 未找到 Maven：请安装 Maven 并加入 PATH，或把便携版放到 runtime\apache-maven-3.9.6\
    pause & exit /b 1
)

rem ---------------- 3. 定位 MySQL 客户端 ----------------
set "MYSQL_BIN="
where mysql >nul 2>&1
if not errorlevel 1 set "MYSQL_BIN=mysql"
if not defined MYSQL_BIN for %%p in (
    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
    "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"
    "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
) do if exist %%p set "MYSQL_BIN=%%~p"
if not defined MYSQL_BIN set "MYSQL_BIN=mysql"

rem 数据库账号：可用环境变量覆盖（DB_USER / DB_PASSWORD）
if not defined DB_USER set "DB_USER=root"
if not defined DB_PASSWORD set "DB_PASSWORD=123456"

echo [1/4] 检查 MySQL 服务...
sc query MySQL80 >nul 2>&1
if errorlevel 1 (
    echo       未检测到 MySQL80 服务，尝试启动...
    net start MySQL80 >nul 2>&1
) else (
    echo       MySQL80 服务存在
)

echo [2/4] 检查数据库 classroom...
"%MYSQL_BIN%" -u%DB_USER% -p%DB_PASSWORD% -e "CREATE DATABASE IF NOT EXISTS classroom DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>nul
if errorlevel 1 (
    echo       [警告] 无法连接 MySQL：请确认服务已启动、账号 %DB_USER% 密码可用
    echo       可通过环境变量指定：set DB_USER=root ^&^& set DB_PASSWORD=你的密码
)

echo [3/4] 检查表结构...
"%MYSQL_BIN%" -u%DB_USER% -p%DB_PASSWORD% classroom -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='classroom';" 2>nul | findstr /r "[0-9]" >nul
if errorlevel 1 (
    echo       未检测到表结构，正在执行 db\schema.sql ...
    "%MYSQL_BIN%" -u%DB_USER% -p%DB_PASSWORD% --default-character-set=utf8mb4 classroom < db\schema.sql
) else (
    echo       表结构已存在（如需重建请手动执行 db\schema.sql）
)

echo [4/4] 启动服务（首次启动会自动生成基础数据与测试数据）...
echo.
echo   启动完成后请访问： http://127.0.0.1:8080
echo   演示账号：教师 t001 / 123456 ，学生 20230101 / 123456 ，管理员 admin / 123456
echo   如需强制重建数据： run.bat --seed
echo.

set MAVEN_OPTS=-Dfile.encoding=UTF-8
if "%1"=="--seed" (
    call "%MVN%" spring-boot:run -Dspring-boot.run.arguments=--seed
) else (
    call "%MVN%" spring-boot:run
)

pause
