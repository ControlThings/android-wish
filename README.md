# Wish core port for Android

This repository is the Wish core port to Android. The port takes form of an Android library (aar) that can be included as an app dependency.

## Build instructions

### Prerequisites

The library uses the wish-c99 library as a submodule:

```
git submodule update --init --recursive
```

You must have you environment configured so that Gradle can find the Android SDK. For example, you need to have a `local.properties` file with following contents:

```
sdk.dir=/home/jan/Android/Sdk
```

As the library includes C code, Android NDK must be installed. You can install it with Android Studio's "SDK Manager", for example. 
Look at ndk version in app/build.gradle, 28.2.13676358

### JDK version

Java: We are currently building with openjdk version "17.0.17" 2025-10-21

### Setting up Github packages

Setup github credentials so that the resulting artifact can be published. This step is needed in order to do a gradle sync.

Create github.properties in repo root, and add to .gitignore; 

```
gpr.usr=
gpr.key=
```

Then visit this page to get your Github id: https://api.github.com/users/<username> like https://api.github.com/users/janyman

Take "id" field, and insert into `github.properties` as `grp.usr`.

Then create a publishing key for you, by visinting https://github.com/settings/tokens
Create a token that has "package write" permission, cut&paste into `github.properties` as `grp.key`


## Actual build

1. Tag the release: 

```
git tag 0.9.1
```

2. Assemble the release artifact

```
./gradlew assembleRelease
```


```
./gradlew --refresh-dependencies clean build assembleRelease
```

3. Publish the 'aar' artifact

This publishes to Github packages:

```
./gradlew publish 
```

The publishing is defined in `app/build.gradle` and requires that you have Github packages set up.


## Nice to know:

./gradlew androidDependencies

