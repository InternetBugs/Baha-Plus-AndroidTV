#!/bin/sh
# Gradle Wrapper launcher. SPDX-License-Identifier: Apache-2.0
project_dir=$(dirname "$0")
if [ -n "$JAVA_HOME" ]; then
  java_executable="$JAVA_HOME/bin/java"
else
  java_executable=java
fi
exec "$java_executable" "-Dorg.gradle.appname=gradlew" -classpath "$project_dir/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
