package ch.epfl.biop.qupath.utils;

import ch.epfl.biop.qupath.plugins.SimpleThresholdDetection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.geom.Point2;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.AreaROI;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.PathROIToolsAwt;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.interfaces.PathArea;
import qupath.lib.roi.interfaces.PathShape;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class PathUtils extends QP {

    // Call a logger so we can write to QuPath's log windows as needed
    private final static Logger logger = LoggerFactory.getLogger(SimpleThresholdDetection.class);

    /**
     * returns the area of the current PathObject in calibrated units. If the area is not defined (like points) it returns 0
     * @param object the object to try and compute the area from
     * @return
     */
    public static double getAreaMicrons(PathObject object) {
        double pixel_size = getCurrentImageData( ).getServer( ).getAveragedPixelSizeMicrons( );
        Double area = getArea( object );
        return area * pixel_size * pixel_size;
    }

    /**
     * returns the area of the current PathObject in pixels
     * @param object the object to try and compute the area from
     * @return
     */
    public static double getArea(PathObject object) {
        ROI roi = object.getROI();
        if (roi instanceof AreaROI) {
            return ( (AreaROI) roi ).getArea( );
        }
        logger.warn( "Area for PathObject {} is undefined because it is of class {}", object.getDisplayedName(), object.getClass().toString() );
        return 0;
    }


        /**
         * Splits the defined pathobject using the provided splitter. it's basically a subtract but
         * NOTE that this method will not return any shapes with holes, so please be aware of this limitation
         * objects that end up separated become new PathObjects
         *
         * @param pathObject the object that will be split.
         * @param splitter   the object used for splitting
         * @return a List of PathObject with the resulting Paths
         */
    public static List<PathObject> splitObject(PathObject pathObject, PathObject splitter) {

        // Convert the line to an Area, so we can use combineROIs
        ROI area = splitter.getROI() instanceof LineROI ? LineToArea(splitter.getROI(), 2) : splitter.getROI();

        PathShape splitObject = PathROIToolsAwt.combineROIs((PathShape) pathObject.getROI(), (PathShape) area, PathROIToolsAwt.CombineOp.SUBTRACT);

        // This method, by Pete, separates the areas into separate polygons
        PolygonROI[][] split = PathROIToolsAwt.splitAreaToPolygons((AreaROI) splitObject);

        List<PathObject> objects = new ArrayList<>(split[1].length);
        for (int i = 0; i < split[1].length; i++) {
            objects.add(new PathAnnotationObject(split[1][i]));
        }

        return objects;
    }

    /**
     * Converts a line to a very thin 4 sided polygon, so it has an area
     *
     * @param line      the line roi to transform
     * @param thickness how thick the polygon should be (the width of the rectangle)
     * @return a new ROI that is a PolygonROI
     */
    public static ROI LineToArea(ROI line, double thickness) {
        if (line instanceof LineROI) {
            // Get the points, offset them 90 degrees
            double px1 = ((LineROI) line).getX1();
            double px2 = ((LineROI) line).getX2();
            double py1 = ((LineROI) line).getY1();
            double py2 = ((LineROI) line).getY2();

            double dx = px2 - px1;
            double dy = py2 - py1;

            double norm = Math.sqrt(dx * dx + dy * dy);

            double normalx = -1 * dy / norm;
            double normaly = dx / norm;
            // define 4 points separated by thickness
            ArrayList<Point2> points = new ArrayList<Point2>(4);
            double p1x = px1 + normalx * thickness / 2;
            double p1y = py1 + normaly * thickness / 2;
            points.add(new Point2(p1x, p1y));

            double p2x = px1 - normalx * thickness / 2;
            double p2y = py1 - normaly * thickness / 2;
            points.add(new Point2(p2x, p2y));

            double p3x = px2 - normalx * thickness / 2;
            double p3y = py2 - normaly * thickness / 2;
            points.add(new Point2(p3x, p3y));

            double p4x = px2 + normalx * thickness / 2;
            double p4y = py2 + normaly * thickness / 2;
            points.add(new Point2(p4x, p4y));

            ROI result = new PolygonROI(points, line.getC(), line.getZ(), line.getT());
            PathDetectionObject def = new PathDetectionObject(result);
            return result;
        }
        return null;
    }

    /**
     * This method tries to merge all the pathobjects that are touching, but keeping all others intact
     *
     * @param objects
     * @return
     */
    public static List<PathObject> mergeTouchingPathObjects(List<PathObject> objects) {
        logger.info("Merging Touching objects from list with {} elements", objects.size());
        List<HashSet<PathObject>> candidates = objects.parallelStream().map( ob1 -> {
            // First check if the bounding boxes touch, which will define those that are worth doing all the mess for
            Area s1 = PathROIToolsAwt.getArea(ob1.getROI());

            HashSet<PathObject> touching = objects.parallelStream().filter(ob2 -> {
                if (boundsOverlap(ob1,ob2) ){

                    Area s2 = PathROIToolsAwt.getArea(ob2.getROI());
                    s2.intersect(s1);
                    return !s2.isEmpty();

                } else {
                    return false;
                }
            }).collect(Collectors.toCollection(HashSet::new));

            return touching;
        }).collect(Collectors.toList());

        logger.info("Looking for candidate merges done {} candidates", candidates.size());

        logger.info("Removing single objects...");

        // Need to see if any element in each list matches, if that's the case we add them all to the first list and remove the older lis
        // remove all the ones that are alone, we do not touch these
        List<HashSet<PathObject>> forSort = candidates.stream().filter(touching -> touching.size() > 1).collect(Collectors.toList());

        List<HashSet<PathObject>> untouched = candidates.stream().filter(touching -> touching.size() == 1).collect(Collectors.toList());

        logger.info("Removing single candidates done: {} candidates left", forSort.size());

// Go through it checking that there are no duplicates and remove them if any
        for (int i = forSort.size()-1; i >= 0; i--) {
            for (int j = i-1; j >= 0; j--) {
                int finalJ = j;
                if (forSort.get(i).stream().anyMatch(forSort.get(finalJ)::contains)) {
                    forSort.get(i).addAll(forSort.get(j));
                    forSort.remove(forSort.get(j));
                    i--;
                }
            }
        }

        forSort.addAll(untouched);

        List<PathObject> result = forSort.stream().map( candidate -> mergePathObjects(new ArrayList<PathObject>(candidate))).collect(Collectors.toList());

        return result;
    }


    public static PathObject mergePathObjects(List<PathObject> pathobjects) {
        // Get all the selected annotations with area
        PathShape shapeNew = null;
        List<PathObject> children = new ArrayList<>();
        for (PathObject child : pathobjects) {
            if (child.getROI() instanceof PathArea) {
                if (shapeNew == null)
                    shapeNew = (PathShape) child.getROI();//.duplicate();
                else
                    shapeNew = PathROIToolsAwt.combineROIs(shapeNew, (PathArea) child.getROI(), PathROIToolsAwt.CombineOp.ADD);
                children.add(child);
            }
        }
        // Check if we actually merged anything
        if (children.isEmpty())
            return null;
        if (children.size() == 1)
            return children.get(0);

        // Create and add the new object, removing the old ones

        PathObject pathObjectNew = null;

        if (pathobjects.get(0) instanceof PathDetectionObject) {
            pathObjectNew = new PathDetectionObject(shapeNew);
        } else {
            pathObjectNew = new PathAnnotationObject(shapeNew);
        }

        return pathObjectNew;
    }

    private static boolean boundsOverlap(PathObject ob1, PathObject ob2) {
        double x11 = ob1.getROI().getBoundsX();
        double y11 = ob1.getROI().getBoundsY();
        double x12 = x11 + ob1.getROI().getBoundsWidth();
        double y12 = y11 + ob1.getROI().getBoundsHeight();

        double x21 = ob2.getROI().getBoundsX();
        double y21 = ob2.getROI().getBoundsY();
        double x22 = x21 + ob2.getROI().getBoundsWidth();
        double y22 = y21 + ob2.getROI().getBoundsHeight();

        return x12 >= x21 && x22 >= x11 && y12 >= y21 && y22 >= y11;

    }
}

