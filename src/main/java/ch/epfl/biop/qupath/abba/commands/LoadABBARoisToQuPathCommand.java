package ch.epfl.biop.qupath.abba.commands;

import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.tools.IJTools;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class LoadABBARoisToQuPathCommand implements Runnable {


    final static Logger logger = LoggerFactory.getLogger(ImageDisplay.class);
    private QuPathGUI qupath;
    private static String title = "Load ABBA RoiSets from current QuPath project";

    //ApplyDisplaySettingsCommand
    public LoadABBARoisToQuPathCommand(final QuPathGUI qupath) {
        if (!Dialogs.showConfirmDialog("Load ABBA RoiSets into QuPath", "This will load any RoiSets exported using ABBA and add them to each image as a hierarchy or Annotations.\nContinue?"))
            return;
        this.qupath = qupath;

    }

    public void run() {
        Project project = qupath.getProject();

        // Loop through each ImageEntry
        final List<ProjectImageEntry> entries = project.getImageList();
        entries.parallelStream().forEach(entry -> {
            Path roisetPath = Paths.get(entry.getEntryPath().toString(), "roiset.zip");
            if (!Files.exists(roisetPath)) return;

            try {
                ImageData imageData = entry.readImageData();
                // Get all the ROIs and add them as PathAnnotations
                RoiManager rm = new RoiManager();
                rm.runCommand("Open", roisetPath.toAbsolutePath().toString());
                Roi[] rois = rm.getRoisAsArray();

                for (Roi roi : rois) {
                    PathObject object = PathObjects.createAnnotationObject(IJTools.convertToROI(roi, 0, 0, 1, null));
                    object.setName(roi.getName());
                    imageData.getHierarchy().addPathObject(object);
                }

                entry.saveImageData(imageData);

            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}