#!/bin/bash

# Compiles the Java Server
# Note the use of the mysql-connector-java-5.1.40 jar

javac -cp lib/mysql-connector-java-5.1.40-bin.jar -d bin/ src/*.java
