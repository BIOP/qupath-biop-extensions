# BIOP Tools Extension

This extension addresses a few needs from our platform and from our users

## Commands

We have implemented 3 commands to work on an image's brightness & contrast settings. These are in the `BIOP > Display Settings...` Menu
 
 * **Save current display settings to file**: Saves a JSON file with the current brighness and contrast settings as well as lookup tables of the current image. Use in conjunction with **Load display settings from file**.
 
 * **Load display settings from file**: Reloads a previous display settings file and tries to apply it to the current image
 
 * **Apply display settings to similar images in project...** will apply the display settings of the current image to all the images in the project provided they have the same number of channels and are of the same type.
 
 ## Scripting helpers
 
 We've created shortcuts to help us with writing scripts. The full list you can get yourself if you generate the javadoc with Gradle. The javadoc is available in a separate project here: https://biop.github.io/qupath-biop-extensions-docs/
 
 