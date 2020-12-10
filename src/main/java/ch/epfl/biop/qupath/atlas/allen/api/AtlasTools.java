package ch.epfl.biop.qupath.atlas.allen.api;

import ch.epfl.biop.atlas.allen.AllenOntologyJson;
import ch.epfl.biop.qupath.atlas.allen.utils.RoiSetLoader;
import ij.gui.Roi;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AtlasTools {

    final static Logger logger = LoggerFactory.getLogger(AtlasTools.class);

    private static QuPathGUI qupath = QuPathGUI.getInstance();

    private static String title = "Load ABBA RoiSets from current QuPath project";

    final static private String ALLEN_ONTOLOGY_FILENAME = "AllenMouseBrainOntology.json";
    final static private String ATLAS_ROISET_FILENAME = "ABBA-RoiSet.zip";

    static private PathObject createAllenAnnotationHierarchy(List<PathObject> annotations) {

        // Map the ID of the annotation to ease finding parents
        Map<Integer, PathObject> mappedAnnotations =
                annotations
                        .stream()
                        .collect(
                                Collectors.toMap(e -> (int) (e.getMeasurementList().getMeasurementValue("Allen ID")), e -> e)
                        );

        mappedAnnotations.forEach((id, annotation) -> {
            PathObject parent = mappedAnnotations.get((int) annotation.getMeasurementList().getMeasurementValue("Parent Allen ID"));
            if (parent != null)
                parent.addPathObject(annotation);
        });

        // Return just the root annotation from Allen Brain, ID 997
        return mappedAnnotations.get(997);
    }

    static PathObject getWarpedAtlasRegions(ImageData imageData, boolean splitLeftRight) {

        List<PathObject> annotations = getFlattenedWarpedAtlasRegions(imageData, splitLeftRight); // TODO
        if (splitLeftRight) {
            List<PathObject> annotationsLeft = annotations
                    .stream()
                    .filter(po -> po.getPathClass().isDerivedFrom(QP.getPathClass("Left")))
                    .collect(Collectors.toList());

            List<PathObject> annotationsRight = annotations
                    .stream()
                    .filter(po -> po.getPathClass().isDerivedFrom(QP.getPathClass("Right")))
                    .collect(Collectors.toList());

            PathObject rootLeft = createAllenAnnotationHierarchy(annotationsLeft);
            PathObject rootRight = createAllenAnnotationHierarchy(annotationsRight);
            ROI rootFused = RoiTools.combineROIs(rootLeft.getROI(), rootRight.getROI(), RoiTools.CombineOp.ADD);
            PathObject rootObject = PathObjects.createAnnotationObject(rootFused);
            rootObject.setName("Root");
            rootObject.addPathObject(rootLeft);
            rootObject.addPathObject(rootRight);
            return rootObject; // TODO
        } else {
            return createAllenAnnotationHierarchy(annotations);
        }

    }

    public static List<PathObject> getFlattenedWarpedAtlasRegions(ImageData imageData, boolean splitLeftRight) {
        Project project = qupath.getProject();

        // Get the project folder and get the JSON ontology
        AllenOntologyJson ontology = AllenOntologyJson.getOntologyFromFile(Paths.get(Projects.getBaseDirectory(project).getAbsolutePath(), ALLEN_ONTOLOGY_FILENAME).toFile());

        // Loop through each ImageEntry
        ProjectImageEntry entry = project.getEntry(imageData);

        Path roisetPath = Paths.get(entry.getEntryPath().toString(), ATLAS_ROISET_FILENAME);
        if (!Files.exists(roisetPath)) {
            logger.info("No RoiSets found in {}", roisetPath);
            return null;
        }

        // Get all the ROIs and add them as PathAnnotations
        List<Roi> rois = RoiSetLoader.openRoiSet(roisetPath.toAbsolutePath().toFile());
        logger.info("Loading {} Allen Regions for {}", rois.size(), entry.getImageName());

        Roi left = rois.get(rois.size() - 2);
        Roi right = rois.get(rois.size() - 1);

        rois.remove(left);
        rois.remove(right);

        List<PathObject> annotations = rois.stream().map(roi -> {
            // Create the PathObject
            PathObject object = PathObjects.createAnnotationObject(IJTools.convertToROI(roi, 0, 0, 1, null));

            // Add metadata to object as acquired from the Ontology
            int object_id = Integer.parseInt(roi.getName());
            // Get associated information
            ch.epfl.biop.atlas.allen.AllenOntologyJson.AllenBrainRegion region = ontology.getRegionFromId(object_id);
            object.setName(region.name);
            object.getMeasurementList().putMeasurement("Allen ID", region.id);
            object.getMeasurementList().putMeasurement("Parent Allen ID", region.parent_structure_id);
            object.getMeasurementList().putMeasurement("Side", 0);
            object.setPathClass(QP.getPathClass(region.acronym));
            object.setLocked(true);
            Color c = Color.web(region.color_hex_triplet);
            int color = ColorTools.makeRGB((int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
            object.setColorRGB(color);
            return object;

        }).collect(Collectors.toList());

        if (splitLeftRight) {
            ROI leftROI = IJTools.convertToROI(left, 0, 0, 1, null);
            ROI rightROI = IJTools.convertToROI(right, 0, 0, 1, null);
            List<PathObject> splitObjects = new ArrayList<>();
            for (PathObject annotation : annotations) {
                ROI shapeLeft = RoiTools.combineROIs(leftROI, annotation.getROI(), RoiTools.CombineOp.INTERSECT);
                if (!shapeLeft.isEmpty()) {
                    PathObject objectLeft = PathObjects.createAnnotationObject(shapeLeft, annotation.getPathClass(), annotation.getMeasurementList());
                    objectLeft.setName(annotation.getName());
                    objectLeft.setPathClass(QP.getDerivedPathClass(QP.getPathClass("Left"), annotation.getPathClass().getName()));
                    objectLeft.setColorRGB(annotation.getColorRGB());
                    objectLeft.setLocked(true);
                    splitObjects.add(objectLeft);
                }

                ROI shapeRight = RoiTools.combineROIs(rightROI, annotation.getROI(), RoiTools.CombineOp.INTERSECT);
                if (!shapeRight.isEmpty()) {
                    PathObject objectRight = PathObjects.createAnnotationObject(shapeRight, annotation.getPathClass(), annotation.getMeasurementList());
                    objectRight.setName(annotation.getName());
                    objectRight.setPathClass(QP.getDerivedPathClass(QP.getPathClass("Right"), annotation.getPathClass().getName()));
                    objectRight.setColorRGB(annotation.getColorRGB());
                    objectRight.setLocked(true);
                    splitObjects.add(objectRight);
                }

            }
            return splitObjects;
        } else {
            return annotations;
        }
    }

    public static void loadWarpedAtlasAnnotations(ImageData imageData, boolean splitLeftRight) {
        imageData.getHierarchy().addPathObject(getWarpedAtlasRegions(imageData, splitLeftRight));
        imageData.getHierarchy().fireHierarchyChangedEvent(AtlasTools.class);
    }

}
