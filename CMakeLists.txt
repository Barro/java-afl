# Copyright 2018  Jussi Judin
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

cmake_minimum_required(VERSION 2.8)

project(java-afl)

# ASM 6.1 Dependency:
set(ASM_JAR ${CMAKE_CURRENT_BINARY_DIR}/asm-6.1.jar)
file(
    DOWNLOAD
        https://search.maven.org/remotecontent?filepath=org/ow2/asm/asm/6.1/asm-6.1.jar
        ${ASM_JAR}
    EXPECTED_HASH
        SHA256=db788a985a2359666aa29a9a638f03bb67254e4bd5f453a32717593de887b6b1)
add_custom_command(OUTPUT ${ASM_JAR} COMMAND cmake -E touch_nocreate "${ASM_JAR}")
add_custom_target(asm-jar DEPENDS ${ASM_JAR})

find_package(JNI REQUIRED)
find_package(Java REQUIRED)
include(UseJava)

add_jar(java-afl-java-javah javafl/JavaAfl.java)
set(javah-include-dir ${CMAKE_CURRENT_BINARY_DIR}/java-afl-javah)
create_javah(
    TARGET java-afl-javah
    GENERATED_FILES java-afl-jni-headers
    CLASSES javafl.JavaAfl
    OUTPUT_DIR ${javah-include-dir}
    CLASSPATH java-afl-java-javah)

add_library(java-afl-lib SHARED JavaAfl.c)
target_include_directories(
    java-afl-lib PRIVATE ${JNI_INCLUDE_DIRS} ${javah-include-dir})
add_dependencies(java-afl-lib java-afl-javah)


find_program(PROGRAM_AFL_SHOWMAP afl-showmap)
if (NOT PROGRAM_AFL_SHOWMAP STREQUAL PROGRAM_AFL_SHOWMAP-NOTFOUND)
    execute_process(
        COMMAND afl-showmap -b -o binary.map -- true
        OUTPUT_QUIET ERROR_QUIET)
    execute_process(
        COMMAND wc -c binary.map
        OUTPUT_VARIABLE AFL_MAP_SIZE_RAW)
    string(STRIP "${AFL_MAP_SIZE_RAW}" AFL_MAP_SIZE_RAW)
    string(REPLACE " " ";" AFL_MAP_SIZE_LIST "${AFL_MAP_SIZE_RAW}")
    list(GET AFL_MAP_SIZE_LIST 0 AFL_MAP_SIZE)
    target_compile_definitions(java-afl-lib PRIVATE -DMAP_SIZE=${AFL_MAP_SIZE})
endif()

set(CMAKE_JAVA_INCLUDE_PATH ${ASM_JAR})
add_jar(java-afl-inject
    SOURCES javafl/JavaAflInject.java javafl/JavaAfl.java
    ENTRY_POINT JavaAflInstrument
    OUTPUT_NAME java-afl-classes
    )

add_custom_command(
    OUTPUT injected
    COMMAND ${CMAKE_COMMAND} -E remove_directory injected/
    COMMAND ${CMAKE_COMMAND} -E make_directory injected/
    COMMAND ${CMAKE_COMMAND} -E remove_directory injected-work/
    COMMAND ${CMAKE_COMMAND} -E make_directory injected-work/
    COMMAND cd injected-work
    COMMAND ${Java_JAR_EXECUTABLE} -xf $<TARGET_PROPERTY:java-afl-inject,JAR_FILE>
    COMMAND ${Java_JAR_EXECUTABLE} -xf ${ASM_JAR}
    COMMAND cd ..
    COMMAND ${Java_JAVA_EXECUTABLE} -cp injected-work/
        javafl.JavaAflInject injected-work/javafl/JavaAfl.class $<TARGET_FILE:java-afl-lib>
    COMMAND ${CMAKE_COMMAND} -E copy injected-work/javafl/JavaAfl.class injected/javafl/JavaAfl.class
    DEPENDS java-afl-inject java-afl-lib
)
add_custom_target(java-afl-injected DEPENDS injected)

# set(CMAKE_JAVA_INCLUDE_PATH ${CMAKE_JAVA_INCLUDE_PATH})
add_jar(java-afl-base
    SOURCES
        javafl/CustomInit.java
        javafl/fuzz.java
        javafl/JavaAflInstrument.java
        javafl/run.java
    ENTRY_POINT javafl.JavaAflInstrument
    )
add_dependencies(java-afl-base asm-jar)

add_custom_command(
    OUTPUT java-afl-instrument.jar
    COMMAND ${CMAKE_COMMAND} -E remove_directory combined-instrument/
    COMMAND ${CMAKE_COMMAND} -E make_directory combined-instrument/javafl
    COMMAND cd combined-instrument/
    COMMAND ${Java_JAR_EXECUTABLE} -xf $<TARGET_PROPERTY:java-afl-base,JAR_FILE>
    COMMAND ${Java_JAR_EXECUTABLE} -xf ${ASM_JAR}
    COMMAND ${CMAKE_COMMAND} -E copy ${CMAKE_CURRENT_BINARY_DIR}/injected/javafl/JavaAfl.class javafl/
    COMMAND ${Java_JAR_EXECUTABLE} -cfe ${CMAKE_CURRENT_BINARY_DIR}/java-afl-instrument.jar javafl.JavaAflInstrument .
    DEPENDS java-afl-injected asm-jar
    )
add_custom_target(java-afl-instrument DEPENDS java-afl-instrument.jar)

add_custom_command(
    OUTPUT java-afl-run.jar
    COMMAND ${CMAKE_COMMAND} -E remove_directory combined-run/
    COMMAND ${CMAKE_COMMAND} -E make_directory combined-run/javafl
    COMMAND cd combined-run/
    COMMAND ${Java_JAR_EXECUTABLE} -xf $<TARGET_PROPERTY:java-afl-base,JAR_FILE>
    COMMAND ${Java_JAR_EXECUTABLE} -xf ${ASM_JAR}
    COMMAND ${CMAKE_COMMAND} -E copy ${CMAKE_CURRENT_BINARY_DIR}/injected/javafl/JavaAfl.class javafl/
    COMMAND ${Java_JAR_EXECUTABLE} -cfe ${CMAKE_CURRENT_BINARY_DIR}/java-afl-run.jar javafl.run .
    DEPENDS java-afl-injected asm-jar
    )
add_custom_target(java-afl-run DEPENDS java-afl-run.jar)

add_jar(java-afl-all
    SOURCES
    # This is just a dummy source file that enables to run this target:
        javafl/CustomInit.java
    INCLUDE_JARS java-afl-instrument.jar java-afl-run.jar)
add_dependencies(java-afl-all java-afl-instrument java-afl-run)
