/**
 * Adds to the detections results which are coordinates of the detection
 * in the CCFv3 (tagged "CCFx", "CCFy", "CCFz" in measurement list)
 *
 */

// Necessary import, requires biop-tools-2.0.7, see: https://github.com/BIOP/qupath-biop-extensions
import ch.epfl.biop.qupath.transform.*
import net.imglib2.RealPoint
import qupath.lib.measurements.MeasurementList

import static qupath.lib.gui.scripting.QPEx.* // For intellij editor autocompletion

// Get ABBA transform file located in entry path +
def targetEntry = getProjectEntry()
def targetEntryPath = targetEntry.getEntryPath();

def fTransform = new File (targetEntryPath.toString(),"ABBA-Transform.json")

if (!fTransform.exists()) {
    System.err.println("ABBA transformation file not found for entry "+targetEntry);
    return ;
}

def pixelToCCFTransform = Warpy.getRealTransform(fTransform).inverse(); // Needs the inverse transform

getDetectionObjects().forEach(detection -> {
    RealPoint ccfCoordinates = new RealPoint(3);
    MeasurementList ml = detection.getMeasurementList();
    ccfCoordinates.setPosition([detection.getROI().getCentroidX(),detection.getROI().getCentroidY(),0] as double[]);
    pixelToCCFTransform.apply(ccfCoordinates, ccfCoordinates);
    ml.addMeasurement("CCFx", ccfCoordinates.getDoublePosition(0) )
    ml.addMeasurement("CCFy", ccfCoordinates.getDoublePosition(1) )
    ml.addMeasurement("CCFz", ccfCoordinates.getDoublePosition(2) )
})

