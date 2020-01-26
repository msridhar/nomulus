#!/bin/sh

echo "running build, be patient..."
./gradlew clean compileJava -PcfLocal > /tmp/output.txt
COUNT=$(cat /tmp/output.txt | grep "build()" | cut -f 2 -d ' ' | paste -sd+ - | bc)
echo "${COUNT} calls to build"