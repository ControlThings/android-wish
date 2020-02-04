# Wish core port for Android

This repository is the Wish core port to Android. The port takes form of an Android library (aar) that can be included as an app dependency.

## Build instructions

$ ./gradlew --refresh-dependencies clean build assembleRelease artifactoryPublish

If you build everything from Android studio, then this just publishes
the current build to Artifactory:

$ ./gradlew artifactoryPublish

This requires that you have set up your Artifactory access credentials.
The encrypted passwd is available from Artifactory after logging in. 
The place to put the credentials is: ~/.gradle/gradle.properties

artifactory_username=<artifactory user name>
artifactory_password=<your hashed Artifactory passwd>

Nice to know:

./gradlew androidDependencies

