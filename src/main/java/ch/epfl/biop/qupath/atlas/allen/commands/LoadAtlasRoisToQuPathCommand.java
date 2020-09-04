package ch.epfl.biop.qupath.atlas.allen.commands;

import ch.epfl.biop.qupath.atlas.allen.api.AtlasTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;

public class LoadAtlasRoisToQuPathCommand implements Runnable {
    private static String title = "Load Allen Brain RoiSets for currently open Image";

    final static Logger logger = LoggerFactory.getLogger(ImageDisplay.class);
    private static QuPathGUI qupath;

    final static private String ALLEN_ONTOLOGY_FILENAME = "AllenMouseBrainOntology.json";
    final static private String ATLAS_ROISET_FILENAME = "ABBA-RoiSet.zip";

    //ApplyDisplaySettingsCommand
    public LoadAtlasRoisToQuPathCommand( final QuPathGUI qupath) {
        if (!Dialogs.showConfirmDialog("Load Allen Brain RoiSets into Image", "This will load any RoiSets Exported using the Allen Brain Alignment tool onto the current image.\nContinue?"))
            return;
        this.qupath = qupath;
    }

    public void run() {

        ImageData imageData = qupath.getImageData( );
        AtlasTools.loadWarpedAtlasAnnotations( imageData );

    }

}