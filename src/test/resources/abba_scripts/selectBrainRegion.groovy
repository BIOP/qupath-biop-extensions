/**
 * Once ABBA results have been imported, you can act on a specific region of the brain
 * by starting your command with this script.
 * 
 */


import static qupath.lib.gui.scripting.QPEx.* // For intellij editor autocompletion

def regionClassPath = "Right: SSp-ul" // for instance, provided regions have been splitted before import

selectObjectsByClassification(regionClassPath);

// What if there are several annotations ?


//TODO : do stuff
