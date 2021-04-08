package ch.epfl.biop.qupath.transform;

import net.imglib2.RealPoint;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.ThinplateSplineTransform;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.gui.QuPathApp;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.*;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static qupath.lib.scripting.QP.fireHierarchyUpdate;

/**
 * Utility class which bridges real transformation of the ImgLib2 world and
 * makes it easily usable into JTS world, mainly used by QuPath
 * <p>
 * See initial forum thread : https://forum.image.sc/t/qupath-arbitrarily-transform-detections-and-annotations/49674
 * For documentation regarding this tool, see https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/wsi_registration_fjii_qupath/
 *
 * @author Nicolas Chiaruttini, EPFL, 2021
 * @author Olivier Burri, EPFL, 2021
 */

/* // Script to automate transfer of annotations and detections as well as measurements:

import ch.epfl.biop.qupath.transform.TransformHelper
def imageData = getCurrentImageData()
// Transfer all matching annotations and detections, keep hierarchy, and transfer measurements (true flag)
TransformHelper.transferMatchingAnnotationsToImage(imageData, true, true)
// Computes all intensity measurements in the new image
TransformHelper.addIntensityMeasurements(getAnnotationObjects(), getCurrentServer(), 1, true)

 */

public class TransformHelper {

    final private static Logger logger = LoggerFactory.getLogger(TransformHelper.class);

    // Pattern to match the serialized transform in the same folder as the target image data the
    private static Pattern transformFilePattern = Pattern.compile("transform\\_(?<target>\\d+)\\_(?<source>\\d+)\\.json");

    /**
     * Main class for debugging
     *
     * @param args
     * @throws Exception
     */
    public static void main(String... args) throws Exception {
        //String projectPath = "\\\\svfas6.epfl.ch\\biop\\public\\luisa.spisak_UPHUELSKEN\\Overlay\\qp\\project.qpproj";
        QuPathApp.launch(QuPathApp.class);//, projectPath);
    }

    /**
     * TODO
     *
     * @param targetImageData
     * @param copyMeasurements
     * @throws Exception
     */
    public static void transferMatchingAnnotationsToImage(ImageData targetImageData, boolean copyMeasurements, boolean allowInverse) throws Exception {
        // Find in project the source data that has annotations/detections AND a Serialized RealTransform in the
        List<ProjectImageEntry<BufferedImage>> entries = QP.getProject().getImageList();
        ProjectImageEntry targetEntry = QP.getProject().getEntry(targetImageData);

        String targetID = targetEntry.getID();

        // Find transform file: list all files in folder
        Path targetEntryPath = targetEntry.getEntryPath();
        List<Path> candidateTransformPaths = Files.list(targetEntryPath).filter(path -> {
            Matcher matcher = transformFilePattern.matcher(path.getFileName().toString());
            if (matcher.matches()) {
                return matcher.group("target").equals(targetID);
            }
            return false;
        }).collect(Collectors.toList());

        // If invertible transform are allowed, we need to check through all entries
        if (allowInverse) {
            entries.forEach(entry -> {
                if (!entry.equals(targetEntry)) {
                    try {
                        List<Path> candidateTransformPathsInEntry = Files.list(entry.getEntryPath()).filter(path -> {
                            Matcher matcher = transformFilePattern.matcher(path.getFileName().toString());
                            if (matcher.matches()) {
                                return matcher.group("source").equals(targetID);
                            }
                            return false;
                        }).collect(Collectors.toList());
                        candidateTransformPaths.addAll(candidateTransformPathsInEntry);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        // If the list of Paths is not empty, then we have candidates, yay!
        logger.info("Found {} candidate transforms for image {}", candidateTransformPaths.size(), targetEntry.getImageName());

        // No transforms, we are done
        if (candidateTransformPaths.size() == 0) return;

        List<ProjectImageEntry> candidateSourceEntries = new ArrayList<>();
        List<Path> finalCandidateTransformPaths = new ArrayList<>();
        List<Boolean> candidateIsInverse = new ArrayList<>(); // Keep track if the candidate is an inverse transform or not
        for (Path path : candidateTransformPaths) {
            Matcher matcher = transformFilePattern.matcher(path.getFileName().toString());
            if (matcher.matches()) {
                if (allowInverse) {
                    // Inverse are allowed
                    if (matcher.group("target").equals(targetID)) {
                        // Normal way, inverse not necessary
                        String sourceID = matcher.group("source");
                        Optional<ProjectImageEntry<BufferedImage>> potentialEntry = entries.stream().filter(entry -> entry.getID().equals(sourceID)).findFirst();
                        if (potentialEntry.isPresent()) {
                            candidateSourceEntries.add(potentialEntry.get());
                            finalCandidateTransformPaths.add(path);
                            candidateIsInverse.add(false);
                        } else {
                            logger.info("No Entries with ID {} in project", sourceID);
                        }
                    } else {
                        assert matcher.group("source").equals(targetID);
                        // Inverse way
                        String sourceID = matcher.group("target");
                        Optional<ProjectImageEntry<BufferedImage>> potentialEntry = entries.stream().filter(entry -> entry.getID().equals(sourceID)).findFirst();
                        if (potentialEntry.isPresent()) {
                            candidateSourceEntries.add(potentialEntry.get());
                            finalCandidateTransformPaths.add(path);
                            candidateIsInverse.add(true);
                        } else {
                            logger.info("No Entries with ID {} in project", sourceID);
                        }
                    }
                } else {
                    String sourceID = matcher.group("source");
                    Optional<ProjectImageEntry<BufferedImage>> potentialEntry = entries.stream().filter(entry -> entry.getID().equals(sourceID)).findFirst();
                    if (potentialEntry.isPresent()) {
                        candidateSourceEntries.add(potentialEntry.get());
                        finalCandidateTransformPaths.add(path);
                        candidateIsInverse.add(false);
                    } else {
                        logger.info("No Entries with ID {} in project", sourceID);
                    }
                }
            }
        }
        // At this point we have a list of ProjectImageEntries we can use
        if (candidateSourceEntries.size() == 0) {
            logger.info("Unfortunately, no valid source images were found for {}", targetEntry.getImageName());
        }
        // TODO see what we do when there is more than one entry
        if (candidateSourceEntries.size() > 1) {
            logger.info("Multiple source images found, using only first one: {}", candidateSourceEntries.get(0).getImageName());
        }

        ProjectImageEntry sourceEntry = candidateSourceEntries.get(0);
        Path sourceTransformPath = finalCandidateTransformPaths.get(0);
        Boolean isInverse = candidateIsInverse.get(0);
        // Get the hierarchy of the source
        PathObjectHierarchy sourceHierarchy = sourceEntry.readHierarchy();
        PathObjectHierarchy targetHierarchy = targetImageData.getHierarchy();//.readHierarchy();

        // Rebuild
        RealTransform rt = TransformHelper.getRealTransform(sourceTransformPath.toFile());

        if (isInverse) {
            if (rt instanceof InvertibleRealTransform) {
                rt = ((InvertibleRealTransform) rt).inverse();
            } else {
                throw new UnsupportedOperationException("Candidate transform is not invertible");
            }
        }

        // Makes JTS transformer
        CoordinateSequenceFilter transformer = TransformHelper.getJTSFilter(rt);

        // Transforms all objects below the root object
        List<PathObject> transformedObjects = new ArrayList<>();

        for (PathObject o : sourceHierarchy.getRootObject().getChildObjects()) {
            transformedObjects.add(TransformHelper.transformPathObjectAndChildren(o, transformer, true, copyMeasurements));
        }

        targetHierarchy.addPathObjects(transformedObjects);

        targetImageData.getHierarchy().setHierarchy(targetHierarchy);

        fireHierarchyUpdate();
    }

    /**
     * Add the standard measurements to the newly created path objects
     * @param objects
     * @param server
     * @param downsample
     * @param measureChildren
     * @throws Exception
     */
    public static void addIntensityMeasurements(Collection<PathObject> objects, ImageServer<BufferedImage> server, double downsample, boolean measureChildren) throws Exception{

        List<ObjectMeasurements.Measurements> measurements = Arrays.asList(ObjectMeasurements.Measurements.values());// as List
        List<ObjectMeasurements.Compartments> compartments = Arrays.asList(ObjectMeasurements.Compartments.values());// as List // Won't mean much if they aren't cells...

        for (PathObject object : objects) {
            if (object instanceof PathDetectionObject) {
                ObjectMeasurements.addIntensityMeasurements(server, object, downsample, measurements, compartments);
            }
            if (measureChildren) {
                addIntensityMeasurements(object.getChildObjects(), server, downsample, true);
            }
        }
    }

    /**
     * Recursive approach to transform a PathObject and all its children based on the provided CoordinateSequenceFilter
     * see {@link #transformPathObject(PathObject, CoordinateSequenceFilter, boolean, boolean)}
     *
     * @param object           qupath annotation or detection object
     * @param transform        jts free form transformation
     * @param copyMeasurements whether or not to transfer all the source PathObject Measurements to the resulting PathObject
     */
    public static PathObject transformPathObjectAndChildren(PathObject object, CoordinateSequenceFilter transform, boolean checkGeometryValidity, boolean copyMeasurements) throws Exception {

        PathObject transformedObject = transformPathObject(object, transform, checkGeometryValidity, copyMeasurements);

        if (object.hasChildren()) {
            for (PathObject child : object.getChildObjects()) {
                transformedObject.addPathObject(transformPathObjectAndChildren(child, transform, checkGeometryValidity, copyMeasurements));
            }
        }
        return transformedObject;
    }

    /**
     * Returns a transformed PathObject (Annotation or detection) based
     * on the original geometry of the input path object
     *
     * @param object           qupath annotation or detection object
     * @param transform        jts free form transformation
     * @param copyMeasurements whether or not to transfer all the source PathObject Measurements to the resulting PathObject
     */
    public static PathObject transformPathObject(PathObject object, CoordinateSequenceFilter transform, boolean checkGeometryValidity, boolean copyMeasurements) throws Exception {

        ROI original_roi = object.getROI();

        Geometry geometry = original_roi.getGeometry();

        GeometryTools.attemptOperation(geometry, (g) -> {
            g.apply(transform);
            return g;
        });

        // Handle the case of a cell
        if (checkGeometryValidity) {
            if (!geometry.isValid()) {
                throw new Exception("Invalid geometry for transformed object" + object);
            }
        }
        // TODO comment a bit more
        ROI transformed_roi = GeometryTools.geometryToROI(geometry, original_roi.getImagePlane());

        PathObject transformedObject;
        if (object instanceof PathAnnotationObject) {
            transformedObject = PathObjects.createAnnotationObject(transformed_roi, object.getPathClass(), copyMeasurements ? object.getMeasurementList() : null);
        } else if (object instanceof PathCellObject) {
            // Need to transform the nucleus as well
            ROI original_nuc = ((PathCellObject) object).getNucleusROI();

            Geometry nuc_geometry = original_nuc.getGeometry();

            GeometryTools.attemptOperation(nuc_geometry, (g) -> {
                g.apply(transform);
                return g;
            });
            ROI transformed_nuc_roi = GeometryTools.geometryToROI(nuc_geometry, original_roi.getImagePlane());
            transformedObject = PathObjects.createCellObject(transformed_roi, transformed_nuc_roi, object.getPathClass(), copyMeasurements ? object.getMeasurementList() : null);

        } else if (object instanceof PathDetectionObject) {
            transformedObject = PathObjects.createDetectionObject(transformed_roi, object.getPathClass(), copyMeasurements ? object.getMeasurementList() : null);
        } else {
            throw new Exception("Unknown PathObject class for class " + object.getClass().getSimpleName());
        }

        return transformedObject;
    }

    /**
     * Uses {@link RealTransformDeSerializer} to deserialize a RealTransform object
     *
     * @param f file to deserialize
     * @return an imglib2 RealTransform object
     */
    public static RealTransform getRealTransform(File f) throws FileNotFoundException {
        FileReader fileReader = new FileReader(f.getAbsolutePath());
        return RealTransformDeSerializer.deserialize(fileReader);
    }

    /**
     * Gets an imglib2 realtransform object for a number of landmarks
     *
     * @param moving_pts moving points
     * @param fixed_pts  fixed points
     * @param force2d    returns a 2d realtransform only and ignores 3rd dimension
     * @return
     */
    public static ThinplateSplineTransform getTransform(List<RealPoint> moving_pts, List<RealPoint> fixed_pts, boolean force2d) {
        int nbDimensions = moving_pts.get(0).numDimensions();
        int nbLandmarks = moving_pts.size();

        if (force2d) nbDimensions = 2;

        double[][] mPts = new double[nbDimensions][nbLandmarks];
        double[][] fPts = new double[nbDimensions][nbLandmarks];

        for (int i = 0; i < nbLandmarks; i++) {
            for (int d = 0; d < nbDimensions; d++) {
                fPts[d][i] = fixed_pts.get(i).getDoublePosition(d);
                //System.out.println("fPts["+d+"]["+i+"]=" +fPts[d][i]);
            }
            for (int d = 0; d < nbDimensions; d++) {
                mPts[d][i] = moving_pts.get(i).getDoublePosition(d);
                //System.out.println("mPts["+d+"]["+i+"]=" +mPts[d][i]);
            }
        }

        return new ThinplateSplineTransform(fPts, mPts);
    }

    /**
     * Gets an imglib2 realtransform object and returned the equivalent
     * JTS {@link CoordinateSequenceFilter} operation which can be applied to
     * {@link Geometry}.
     * <p>
     * The 3rd dimension is ignored.
     *
     * @param rt imglib2 realtransform object
     * @return the equivalent JTS {@link CoordinateSequenceFilter} operation which can be applied to {@link Geometry}.
     */
    public static CoordinateSequenceFilter getJTSFilter(RealTransform rt) {
        return new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                RealPoint pt = new RealPoint(3);
                pt.setPosition(seq.getOrdinate(i, 0), 0);
                pt.setPosition(seq.getOrdinate(i, 1), 1);
                rt.apply(pt, pt);
                seq.setOrdinate(i, 0, pt.getDoublePosition(0));
                seq.setOrdinate(i, 1, pt.getDoublePosition(1));
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        };
    }

}
