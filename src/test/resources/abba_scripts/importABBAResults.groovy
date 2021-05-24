/**
 * Import the results of ABBA registration into QuPath
 * No safe guards if no result is found
 *
 * Batchable via "Run for project"
 */

// Necessary import, requires biop-tools-2.0.7, see: https://github.com/BIOP/qupath-biop-extensions
import ch.epfl.biop.qupath.atlas.allen.api.AtlasTools
import qupath.lib.images.ImageData

import static qupath.lib.gui.scripting.QPEx.* // For intellij editor autocompletion

ImageData imageData = getCurrentImageData();
AtlasTools.loadWarpedAtlasAnnotations(imageData, true);