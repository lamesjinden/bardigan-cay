Context : [[HackerOnBoarding]]

This is my simple script for building BardiganCay into a JAR file for distribution. Copy it into a file called something like build.sh.

At the end of running it, you will have the distro of the latest code in ./deploy/bardigan.zip 

This zip contains :
* an UberJAR which can be run with `java -jar ./bardigan.jar`
* a copy of the default bedrock wiki data.
----

```
#! /bin/bash

rm -rf target 
clj -A:prod:app

mkdir -p ./deploy/bardigan
cp ./target/bardigan-cay-1.0.2-SNAPSHOT.jar ./deploy/bardigan/bardigan.jar
cp ./target/bardigan-cay-1.0.2-SNAPSHOT.jar /media/phil/54AE4F563BCE86E8/DATA/dev_tools/jars/bardigan.jar 

rsync -avr --delete-after ./bedrock/ ./deploy/bardigan/bedrock/

echo "java -jar ./bardigan.jar" > ./deploy/bardigan/go.sh
chmod +x ./deploy/bardigan/go.sh
echo "java -jar ./bardigan.jar" > ./deploy/bardigan/go.bat

cd ./deploy/

zip -r ./bardigan.zip bardigan

```


