# EDIT:  2021-Nov-23rd: ARCHIVED REPOSITORY

This repository is now archived, it is read-only. For extensions in QuPath v0.3 check the other repositories:

* https://github.com/BIOP/qupath-extension-biop
* https://github.com/BIOP/qupath-extension-cellpose
* https://github.com/BIOP/qupath-extension-warpy
* https://github.com/BIOP/qupath-extension-abba
* and probably others to come...

# BIOP Tools Extension for QuPath v0.2.3

This extension addresses a few needs from our platform and from our users. For extensions usable with QuPath 0.3.0, please check the other repositories of the BIOP.

## Commands

We have implemented 3 commands to work on an image's brightness & contrast settings. These are in the `BIOP > Display Settings...` Menu
 
 * **Save current display settings to file**: Saves a JSON file with the current brighness and contrast settings as well as lookup tables of the current image. Use in conjunction with **Load display settings from file**.
 
 * **Load display settings from file**: Reloads a previous display settings file and tries to apply it to the current image
 
 * **Apply display settings to similar images in project...** will apply the display settings of the current image to all the images in the project provided they have the same number of channels and are of the same type.
 
 ## Scripting helpers
 
 We've created shortcuts to help us with writing scripts. The full list you can get yourself if you generate the javadoc with Gradle. The javadoc is available in a separate project here: https://biop.github.io/qupath-biop-extensions-docs/
 
 # Installation

 You can download the latest release [jar file](https://github.com/BIOP/qupath-biop-extensions/releases) and drag and drop it into QuPath.
 
 # Downloading the project and working on it
 
To work on this project

To work on this project with your IDE (We are using IntelliJ), you need to also have the qupath project in the same hierarchy.
```
parent directory
├── qupath
│   └── build.gradle
├── qupath-biop-extensions
│   └── build.gradle
```

0. Make sure you have the Java 11 SDK. We used [OpenJDK14](https://jdk.java.net/14/). Extract it to a folder, which we will call `JAVA_14_PATH`
1. Clone the project from here `https://github.com/BIOP/qupath-biop-extensions.git` and place it *in the same parent folder as your qupath repository*

2. Run `gradlew.bat build -Dorg.gradle.java.home="JAVA_14_PATH"`

We used the latest gradle version to do this so if you have any issues, you can run:

`gradlew.bat wrapper --gradle-version 5.3 --distribution-type all -Dorg.gradle.java.home="JAVA_14_PATH"`

We have yet to test this more in depth to make sure that these instructions work for everyone. 

# Known issues
Currently, the build.gradle file is not checking your platform, which is important for JavaFX and is currently configured for windows. This should not be a problem for the JAR file, as it does not bundle JavaFX, but it's worth keeping in mind. 
