# BIOP Tools Extension

This extension addresses a few needs from our platform and from our users

## Commands

We have implemented 3 commands to work on an image's brightness & contrast settings. These are in the `BIOP > Display Settings...` Menu
 
 * **Save current display settings to file**: Saves a JSON file with the current brighness and contrast settings as well as lookup tables of the current image. Use in conjunction with **Load display settings from file**.
 
 * **Load display settings from file**: Reloads a previous display settings file and tries to apply it to the current image
 
 * **Apply display settings to similar images in project...** will apply the display settings of the current image to all the images in the project provided they have the same number of channels and are of the same type.
 
 ## Scripting helpers
 
 We've created shortcuts to help us with writing scripts. The full list you can get yourself if you generate the javadoc with Gradle. The javadoc is available in a separate project here: https://biop.github.io/qupath-biop-extensions-docs/
 
 # Installation

 You can download the latest release [jar file](http://) and drag and drop it into QuPath.
 
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

0. Make sure you have the Java 11 SDK. We used [OpenJDK11](https://jdk.java.net/11/). Extract it to a folder, which we will call `JAVA_11_PATH`
1. Clone the project from here `https://github.com/BIOP/qupath-biop-extensions.git` and place it *in the same parent folder as your qupath repository*

2. Run `gradlew.bat build -Dorg.gradle.java.home="JAVA_11_PATH"`

We used the latest gradle version to do this so if you have any issues, you can run:

`gradlew.bat wrapper --gradle-version 5.3 --distribution-type all -Dorg.gradle.java.home="JAVA_11_PATH"`

We have yet to test this more in depth to make sure that these instructions work for everyone. 

# Known issues
Currently, the build.gradle file is not checking your platform, which is important for JavaFX and is currently configured for windows. This should not be a problem for the JAR file, as it does not bundle JavaFX, but it's worth keeping in mind. 
