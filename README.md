# Thermal-Detector
Android app for detecting warm objects in dark environments. Requires an integrated FLIR camera on the phone.

## Device compatibility
This app requires the phone to have an integrated FLIR thermal camera.
External thermal cameras likely do not work.
Compatible phones likely come with an app called "MyFLIR" preinstalled.
The app is tested on a Ulefone Power Armor 19T.
If the app is verified to work on other devices then a compatibility list can be added.

## Build
Create a signing key in a new or existing keystore

### Android Studio
- Open the repository as a project in Android Studio
- Navigate the main menu to `Build` -> `Generate Signed App Bundle or APK...`
- Select `APK`
- Open the previously created keystore and fill in the form
- Select the Release variant and build the APKs

### Gradle
- Create a file `keystore.properties` in the project root
- Define the following properties in the file:
    - `RELEASE_STORE_FILE=<path to the previously created keystore>`
    - `RELEASE_STORE_PASSWORD=<password for the keystore>`
    - `RELEASE_KEY_ALIAS=<name of the key to use>`
    - `RELEASE_KEY_PASSWORD=<password of the key to use>`
- Execute `gradle assembleRelease` to build the APKs

## FLIR SDK
The FLIR sdk comes as a .zip file, which contains: file `androidsdk-release.aar`, file `thermalsdk-release.aar`, folder `javadoc`.
To update the SDK version, create a local maven artifact for them in the folder `libs`.
In `app/build.gradle` update the versions of `androidsdk` and `androidsdk` to the new SDK version.

### Create artifacts from SDK files
- Zip the contents of `javadoc` from the SDK. Make sure that `javadoc.zip` does not contain a nested folder `javadoc`
- Create a new folder `libs/com/flir/androidsdk/<sdk-version>`
- Rename `androidsdk-release.aar` from the SDK to `androidsdk-<sdk-version>.aar` and place in the new folder
- Copy and rename `javadoc.zip` to `androidsdk-<sdk-version>-javadoc.jar` and place in the new folder
- Copy `libs/com/flir/androidsdk/2.15.0/androidsdk-2.15.0.pom` to the new folder and rename to `androidsdk-<sdk-version>.pom`
- Update the version tag in `androidsdk-<sdk-version>.pom` to contain <sdk-version>
- Create a new folder `libs/com/flir/thermalsdk/<sdk-version>`
- Rename `thermalsdk-release.aar` from the SDK to `thermalsdk-<sdk-version>.aar` and place in the new folder
- Copy and rename `javadoc.zip` to `thermalsdk-<sdk-version>-javadoc.jar` and place in the new folder
- Copy `libs/com/flir/thermalsdk/2.15.0/thermalsdk-2.15.0.pom` to the new folder and rename to `thermalsdk-<sdk-version>.pom`
- Update the version tag in `thermalsdk-<sdk-version>.pom` to contain <sdk-version>
