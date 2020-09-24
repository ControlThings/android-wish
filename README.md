# Wish core port for Android

This repository is the Wish core port to Android. The port takes form of an Android library (aar) that can be included as an app dependency.

## Build instructions

### Prerequisites

The library uses the wish-c99 library as a submodule:

```
git submodule update --init --recursive
```

As the library includes C code, Android NDK must be installed. You can install it with Android Studio's "SDK Manager", for example. 

Beware! Currently compiling is only successful with NDK version 16.1.4479499.

You must have you environment configured so that Gradle can find the Android SDK. For example, you need to have a `local.properties` file with following contents:

```
ndk.dir=/home/jan/Android/Sdk/ndk/16.1.4479499
sdk.dir=/home/jan/Android/Sdk
```

## Build instructions

```
./gradlew --refresh-dependencies clean build assembleRelease
```

## Nice to know:

./gradlew androidDependencies

