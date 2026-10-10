@echo off
rem ============================================================================
rem  Lucky Agent 共用 JDK 21+ 定位脚本（内部辅助，不单独对外）
rem
rem  用法：call "%~dp0_jdk.bat"  然后判断 if errorlevel 1
rem  成功：导出 JAVA_HOME 与 JAVA_EXE，返回码 0
rem  失败：打印可行动指引，返回码 1
rem
rem  为什么需要它：本产品编译目标为 Java 21（class file version 65.0）。
rem  若用 PATH 上的 JDK 17 启动，JVM 会在 Spring 启动之前抛出
rem  UnsupportedClassVersionError，用户几乎无法自行归因到「JDK 版本不对」。
rem  修复前 lucky.bat / lucky-cli.bat 都是裸调 `java`，本机 PATH 上恰是 17，
rem  导致两个启动器都直接崩溃。
rem
rem  解析顺序：%LUCKY_JDK_HOME% > %JAVA_HOME% > 常见安装位置 > PATH
rem
rem  刻意不写 setlocal：由调用方（lucky.bat / lucky-cli.bat 已有 setlocal）
rem  负责作用域回收，这样 JAVA_EXE 能被调用方看见，又不会污染用户 shell。
rem ============================================================================

set "JAVA_HOME="
set "JAVA_EXE="
set "JDK_DIR="

call :probe "%LUCKY_JDK_HOME%\bin\java.exe"

if not defined JAVA_EXE call :probe "%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE call :probe "D:\jdk21\bin\java.exe"
if not defined JAVA_EXE call :probe "D:\Java\jdk-21\bin\java.exe"
if not defined JAVA_EXE call :probe "C:\Program Files\Java\jdk-21\bin\java.exe"
if not defined JAVA_EXE call :probe "C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe"

rem 最后兜底：PATH 上的 java（仅当版本合格才会被采纳）
if not defined JAVA_EXE for /f "delims=" %%p in ('where java 2^>nul') do (
    if not defined JAVA_EXE call :probe "%%~fp"
)

if defined JAVA_EXE (
    set "JAVA_HOME=%JDK_DIR%"
    rem 不在此回显 JDK 路径：它对普通使用只是噪声，而「到底用了哪个 JDK」由 /doctor
    rem 如实报告（JVM 版本 + 框架根），排障时看那里即可。
    exit /b 0
)

echo.
echo [错误] 未找到可用的 JDK 21 或更高版本。
echo.
echo   本产品需要 JDK 21+（用 JDK 17 启动会立即报
echo   UnsupportedClassVersionError: class file version 65.0）。
echo.
echo   请任选其一后重试：
echo     1^) set LUCKY_JDK_HOME=D:\jdk21     ^(推荐^，只影响当前窗口^)
echo     2^) set JAVA_HOME=D:\jdk21
echo     3^) 把 JDK 21 的 bin 目录加入 PATH
echo.
exit /b 1

rem ---------------------------------------------------------------------------
rem :probe <java.exe 路径候选>
rem 校验该候选存在且主版本 >= 21；合格则设置 JAVA_EXE 与 JDK_DIR。
rem 为避开「for /f 里嵌带空格引号路径」的经典解析陷阱，
rem 版本探测走「先输出到临时文件，再 findstr 读文件」，不做命令嵌套。
rem ---------------------------------------------------------------------------
:probe
set "CAND=%~1"
if not exist "%CAND%" exit /b 0

set "VER="
"%CAND%" -version >"%TEMP%\_lucky_jdk_ver.txt" 2>&1
for /f "tokens=3" %%v in ('findstr /i "version" "%TEMP%\_lucky_jdk_ver.txt"') do set "VER=%%v"
del "%TEMP%\_lucky_jdk_ver.txt" >nul 2>&1

if not defined VER exit /b 0
set "VER=%VER:"=%"
set "MAJ="
for /f "tokens=1,2 delims=." %%a in ("%VER%") do (
    set "MAJ=%%a"
    rem Java 8 及更早的版本串形如 1.8.0_xxx，主版本取第二段
    if "%%a"=="1" set "MAJ=%%b"
)
if not defined MAJ exit /b 0
if %MAJ% LSS 21 exit /b 0

set "JAVA_EXE=%CAND%"
for %%d in ("%CAND%") do set "JDK_DIR=%%~dpd"
rem %%~dpd 形如 D:\jdk21\bin\ ：先去尾部反斜杠，再去尾部 \bin，
rem 得到真正的 JDK 根（JAVA_HOME 必须指向 JDK 根，指向 bin 是错的）
set "JDK_DIR=%JDK_DIR:~0,-1%"
if /i "%JDK_DIR:~-4%"=="\bin" set "JDK_DIR=%JDK_DIR:~0,-4%"
exit /b 0