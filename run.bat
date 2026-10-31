@echo off
chcp 65001 >nul
title 智课堂 · 大学课堂互动与学情分析系统（Spring Boot 版）
cd /d %~dp0

echo ============================================================
echo   智课堂 · 大学课堂互动与学情分析系统
echo   Spring Boot 3.2 + MyBatis + MySQL 8.0 + Thymeleaf
echo ============================================================
echo.

set JAVA_HOME=D:\develop\Java\jdk-17
set MVN=%~dp0runtime\apache-maven-3.9.6\bin\mvn.cmd
set MYSQL_BIN=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe

if not exist "%JAVA_HOME%\bin\java.exe" set JAVA_HOME=%JAVA_HOME%
if not exist "%MVN%" (
    echo [错误] 未找到便携版 Maven：%MVN%
    pause & exit /b 1
)

echo [1/4] 检查 MySQL 服务...
sc query MySQL80 >nul 2>&1
if errorlevel 1 (
    echo       未检测到 MySQL80 服务，尝试启动...
    net start MySQL80 >nul 2>&1
) else (
    echo       MySQL80 服务存在
)
if not exist "%MYSQL_BIN%" set MYSQL_BIN=mysql.exe

echo [2/4] 检查数据库 classroom...
"%MYSQL_BIN%" -u root --password=123456 -e "CREATE DATABASE IF NOT EXISTS classroom DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>nul
if errorlevel 1 (
    echo       [警告] 无法连接 MySQL，请确认服务已启动且 root 密码为 123456
    echo       若密码不同，请修改 src\main\resources\application.yml
)

echo [3/4] 检查表结构...
"%MYSQL_BIN%" -u root --password=123456 classroom -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='classroom';" 2>nul | findstr /r "[0-9]" >nul
if errorlevel 1 (
    echo       未检测到表结构，正在执行 db\schema.sql ...
    "%MYSQL_BIN%" -u root --password=123456 --default-character-set=utf8mb4 classroom < db\schema.sql
) else (
    echo       表结构已存在（如需重建请手动执行 db\schema.sql）
)

echo [4/4] 启动服务（首次启动会自动生成基础数据与测试数据）...
echo.
echo   启动完成后请访问： http://127.0.0.1:8080
echo   演示账号：教师 t001 / 123456 ，学生 20230101 / 123456
echo   如需强制重建数据： run.bat --seed
echo.

set MAVEN_OPTS=-Dfile.encoding=UTF-8
if "%1"=="--seed" (
    call "%MVN%" spring-boot:run -Dspring-boot.run.arguments=--seed
) else (
    call "%MVN%" spring-boot:run
)

pause
