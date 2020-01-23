#!/bin/sh

# note: requires ripgrep to have already been installed: https://github.com/BurntSushi/ripgrep

echo "running build, be patient..."
./gradlew clean compileJava -PcfLocal -PcfShowChecks > /tmp/output.txt
COUNT=$(rg -U --count "build\(\)\n(.*)\n   expected: DECLARED @CalledMethods\(.*\n success" /tmp/output.txt)
echo "${COUNT} verified calls"