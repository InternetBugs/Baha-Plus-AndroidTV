@rem Gradle Wrapper launcher. SPDX-License-Identifier: Apache-2.0
@echo off
setlocal
set "APP_ROOT=%~dp0"
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" "-Dorg.gradle.appname=gradlew" -classpath "%APP_ROOT%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
