#!/bin/zsh
set -eux
rm -rf _build
mkdir _build

# Compile all files
javac -cp $(print lib/**/*.jar | tr ' ' :) -d _build src/**/*.java
cd _build
# This step is to make a standalone jar.
# Comment it out if you want a jar only with te-specific code.
for jar in ../lib/*.jar; jar xf $jar
# The manifest makes it so you can do "java -jar"
(
cat<<-EOF
Main-Class: ui.Main
EOF
) > Manifest.txt
jar cfm ../te.jar Manifest.txt *
cd ..

echo "Done creating jar file:"
ls -l te.jar