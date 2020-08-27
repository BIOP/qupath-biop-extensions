package ch.epfl.biop.qupath.abba.commands;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.tools.IJTools;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LoadABBARoisToQuPathCommand implements Runnable {


    final static Logger logger = LoggerFactory.getLogger(ImageDisplay.class);
    private Project project;
    private static String title = "Load ABBA RoiSets from current QuPath project";

    //ApplyDisplaySettingsCommand
    public LoadABBARoisToQuPathCommand(final Project project) {
        if (!Dialogs.showConfirmDialog("Load ABBA RoiSets into QuPath", "This will load any RoiSets exported using ABBA and add them to each image as a hierarchy or Annotations.\nContinue?"))
            return;
        this.project = project;

    }

    public void run() {
        // Loop through each ImageEntry
        final List<ProjectImageEntry> entries = project.getImageList();
        entries.parallelStream().forEach(entry -> {
            Path roisetPath = Paths.get(entry.getEntryPath().toString(), "ABBA-RoiSet.zip");
            if (!Files.exists(roisetPath)) return;

            try {
                ImageData imageData = entry.readImageData();

                // Get all the ROIs and add them as PathAnnotations
                ArrayList<Roi> rois = openRoiSet(roisetPath.toAbsolutePath().toString());

                List<PathObject> annotations = rois.stream().map(roi -> {
                    PathObject object = PathObjects.createAnnotationObject(IJTools.convertToROI(roi, 0, 0, 1, null));
                    object.setName(roi.getName());
                    // TODO: Add name as class, maybe
                    return object;
                }).collect(Collectors.toList());

                annotations.stream().forEach(object -> {
                    // TODO: If already a roi with that name, do not add
                    // TODO: Resolve hierarchy of pathObjects
                    imageData.getHierarchy().addPathObject( object );
                });

                entry.saveImageData(imageData);

            } catch (IOException e) {
                logger.error("Error Importing RoiSets from ABBA: {}", e.getMessage());
            }
        });

    }

    // Taken directly from the RoiManager, so as to be able to run it concurrently
    // since the RoiManage only allows for one instance of itself to exist...
    private ArrayList<Roi> openRoiSet(String path) {
        ZipInputStream in = null;
        ByteArrayOutputStream out = null;
        int nRois = 0;
        ArrayList<Roi> rois = new ArrayList<>();
        try {
            in = new ZipInputStream(new FileInputStream(path));
            byte[] buf = new byte[1024];
            int len;
            ZipEntry entry = in.getNextEntry();
            while (entry != null) {
                String name = entry.getName();
                if (name.endsWith(".roi")) {
                    out = new ByteArrayOutputStream();
                    while ((len = in.read(buf)) > 0)
                        out.write(buf, 0, len);
                    out.close();
                    byte[] bytes = out.toByteArray();
                    RoiDecoder rd = new RoiDecoder(bytes, name);
                    Roi roi = rd.getRoi();
                    if (roi != null) {
                        name = name.substring(0, name.length() - 4);
                        rois.add(roi);
                        nRois++;
                    }
                }
                entry = in.getNextEntry();
            }
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null)
                try {
                    in.close();
                } catch (IOException e) {
                }
            if (out != null)
                try {
                    out.close();
                } catch (IOException e) {
                }
        }
        if (nRois == 0) {
            logger.error("This ZIP archive does not contain '.roi' files: {}", path);
        }
        return rois;
    }
}