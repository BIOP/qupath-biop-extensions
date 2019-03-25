package ch.epfl.biop.qupath.utils;

import qupath.lib.geom.Point2;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.AreaROI;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.PathROIToolsAwt;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.interfaces.PathShape;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.*;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;

public class PathUtils extends QP {
    /**
     * Splits the defined pathobject using the provided splitter. it's basically a subtract but
     * NOTE that this method will not return any shapes with holes, so please be aware of this limitation
     * objects that end up separated become new PathObjects
     * @param pathObject the object that will be split.
     * @param splitter the object used for splitting
     * @return a List of PathObject with the resulting Paths
     */
    public static List<PathObject> splitObject(PathObject pathObject, PathObject splitter) {

        // Convert the line to an Area, so we can use combineROIs
        ROI area = splitter.getROI() instanceof LineROI ? LineToArea(splitter.getROI(), 2 ) : splitter.getROI();

        PathShape splitObject = PathROIToolsAwt.combineROIs( (PathShape) pathObject.getROI(), (PathShape) area, PathROIToolsAwt.CombineOp.SUBTRACT);

        // This method, by Pete, separates the areas into separate polygons
        PolygonROI[][] split = PathROIToolsAwt.splitAreaToPolygons((AreaROI) splitObject);

        List<PathObject> objects = new ArrayList<>(split[1].length);
        for(int i=0; i<split[1].length; i++) {
            objects.add(new PathAnnotationObject(split[1][i]));
        }

        return objects;
    }

    /**
     * Converts a line to a very thin 4 sided polygon, so it has an area
     * @param line the line roi to transform
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

            double norm = Math.sqrt( dx * dx + dy * dy );

            double normalx = -1 * dy / norm;
            double normaly = dx / norm;
            // define 4 points separated by thickness
            ArrayList<Point2> points = new ArrayList<Point2>(4);
            double p1x = px1 + normalx * thickness/2;
            double p1y = py1 + normaly * thickness/2;
            points.add(new Point2(p1x, p1y));

            double p2x = px1 - normalx * thickness/2;
            double p2y = py1 - normaly * thickness/2;
            points.add(new Point2(p2x, p2y));

            double p3x = px2 - normalx * thickness/2;
            double p3y = py2 - normaly * thickness/2;
            points.add(new Point2(p3x, p3y));

            double p4x = px2 + normalx * thickness/2;
            double p4y = py2 + normaly * thickness/2;
            points.add(new Point2(p4x, p4y));

            ROI result = new PolygonROI(points, line.getC(), line.getZ(), line.getT());
            PathDetectionObject def = new PathDetectionObject(result);
            return result;
        }
        return null;
    }


/*
    public static PathCellObject makeCellObject(PathObject pathObject, double radiusPixels) {

        ROI roi = pathObject.getROI();
        Shape shape = PathROIToolsAwt.getShape(pathObject.getROI());

        Area area = PathROIToolsAwt.shapeMorphology(shape, radiusPixels);

        // If the radius is negative (i.e. a dilation), then the parent will be the original object itself
        boolean isErosion = radiusPixels < 0;

        if (isErosion) {
            Area area2 = new Area(shape);
            area2.subtract(area);
            area = area2;
        } else
            area.subtract(new Area(shape));

        ROI roi2 = PathROIToolsAwt.getShapeROI(area, roi.getC(), roi.getZ(), roi.getT(), 0.5);

        ROI nucleus = isErosion ? roi2 : pathObject.getROI();
        ROI cell = isErosion ? pathObject.getROI() : roi2;

        // Create a new annotation, with properties based on the original
        PathCellObject newCell = new PathCellObject(cell, nucleus, pathObject.getPathClass());
        newCell.setName("Cell " + pathObject.getName());

        newCell.setColorRGB(pathObject.getColorRGB());
        return newCell;
    }

    MeasurementList addFluoMeasurements(PathObject pathObject, double downsampleFactor) {

        QuPathGUI qupath = QuPathGUI.getInstance();

        ImageData imagedata = getCurrentImageData();

        double pxWidth  = imagedata.getServer().getPixelWidthMicrons();
        double pxHeight = imagedata.getServer().getPixelHeightMicrons();
        PathImage<ImagePlus> pathImage = PathImagePlus.createPathImage(imagedata.getServer(), pathObject.getROI(), downsampleFactor);

        Map<String, FloatProcessor> channels = new LinkedHashMap<>();

        ImagePlus imp = pathImage.getImage();
        for (int c = 1; c <= imp.getNChannels(); c++) {
            channels.put("Channel " + c, imp.getStack().getProcessor(imp.getStackIndex(c, 0, 0)).convertToFloatProcessor());
        }



        // Create nucleus objects
        MeasurementList measurementList = MeasurementListFactory.createMeasurementList(30, MeasurementList.TYPE.FLOAT);

        ObjectMeasurements.addShapeStatistics(measurementList, (PolygonROI) pathObject.getROI() , pxWidth, pxHeight, "Nucleus: ");
        ObjectMeasurements.addIntensityMeasurements();
        if(pathObject instanceof PathCellObject) {
            PathCellObject cell = (PathCellObject) pathObject;
            ObjectMeasurements.addShapeStatistics(measurementList, (PolygonROI) cell.getNucleusROI() , pxWidth, pxHeight, "Cell: ");
            // Make cytoplasm


        SimpleImage imgLabels = new PixelImageIJ(ipLabels);
        for (String key : channels.keySet()) {
            java.util.List<RunningStatistics> statsList = StatisticsHelper.createRunningStatisticsList(roisNuclei.size());
            StatisticsHelper.computeRunningStatistics(new PixelImageIJ(channels.get(key)), imgLabels, statsList);
            statsMap.put(key, statsList);
        }

                for (String key : channels.keySet()) {
                    List<RunningStatistics> statsList = statsMap.get(key);
                    RunningStatistics stats = statsList.get(i);
                    measurementList.addMeasurement("Nucleus: " + key + " mean", stats.getMean());
                    measurementList.addMeasurement("Nucleus: " + key + " sum", stats.getSum());
                    measurementList.addMeasurement("Nucleus: " + key + " std dev", stats.getStdDev());
                    measurementList.addMeasurement("Nucleus: " + key + " max", stats.getMax());
                    measurementList.addMeasurement("Nucleus: " + key + " min", stats.getMin());
                    measurementList.addMeasurement("Nucleus: " + key + " range", stats.getRange());
                }
            }

            // TODO: It would be more efficient to measure the hematoxylin intensities along with the shapes
            PathObject pathObject = new PathDetectionObject(pathROI, null, measurementList);
            nucleiObjects.add(pathObject);

        }

        if (Thread.currentThread().isInterrupted())
            return;

        List<Roi> roisCellsList = null;

        // Optionally expand the nuclei to become cells
        if (cellExpansion > 0) {
            FloatProcessor fpEDM = new EDM().makeFloatEDM(bp, (byte)255, false);
            fpEDM.multiply(-1);

            double cellExpansionThreshold = -cellExpansion;

            // Create cell ROIs
            ImageProcessor ipLabelsCells = ipLabels.duplicate();
            Watershed.doWatershed(fpEDM, ipLabelsCells, cellExpansionThreshold, false);
            PolygonRoi[] roisCells = ROILabeling.labelsToFilledROIs(ipLabelsCells, roisNuclei.size());

            // Compute cell DAB stats
            Map<String, List<RunningStatistics>> statsMapCell = new LinkedHashMap<>();
            if (makeMeasurements) {
                for (String key : channelsCell.keySet()) {
                    List<RunningStatistics> statsList = StatisticsHelper.createRunningStatisticsList(roisNuclei.size());
                    StatisticsHelper.computeRunningStatistics(new PixelImageIJ(channelsCell.get(key)), new PixelImageIJ(ipLabelsCells), statsList);
                    statsMapCell.put(key, statsList);
                }
            }

            // Create labelled image for cytoplasm, i.e. remove all nucleus pixels
            // TODO: Make a buffer zone between nucleus and cytoplasm!
            for (int i = 0; i < ipLabels.getWidth() * ipLabels.getHeight(); i++) {
                if (ipLabels.getf(i) != 0)
                    ipLabelsCells.setf(i, 0f);
            }

            // Compute cytoplasm stats
            Map<String, List<RunningStatistics>> statsMapCytoplasm = new LinkedHashMap<>();
            if (makeMeasurements) {
                for (String key : channelsCell.keySet()) {
                    List<RunningStatistics> statsList = StatisticsHelper.createRunningStatisticsList(roisNuclei.size());
                    StatisticsHelper.computeRunningStatistics(new PixelImageIJ(channelsCell.get(key)), new PixelImageIJ(ipLabelsCells), statsList);
                    statsMapCytoplasm.put(key, statsList);
                }
            }


            // Create cell objects
            roisCellsList = new ArrayList<>(roisCells.length); // In case we need texture measurements, store all cell ROIs
            for (int i = 0; i < roisCells.length; i++) {
                PolygonRoi r = roisCells[i];
                if (r == null)
                    continue;
                if (smoothBoundaries)
                    r = new PolygonRoi(r.getInterpolatedPolygon(Math.min(2.5, r.getNCoordinates()*0.1), false), Roi.POLYGON); // TODO: Check this smoothing - it can be troublesome, causing nuclei to be outside cells
//						r = smoothPolygonRoi(r);

                PolygonROI pathROI = ROIConverterIJ.convertToPolygonROI(r, pathImage.getImage().getCalibration(), pathImage.getDownsampleFactor(), 0, z, t);
                if (smoothBoundaries)
                    pathROI = ShapeSimplifier.simplifyPolygon(pathROI, pathImage.getDownsampleFactor()/4.0);


                MeasurementList measurementList = null;
                PathObject nucleus = null;
                if (includeNuclei) {
                    // Use the nucleus' measurement list
                    nucleus = nucleiObjects.get(i);
                    measurementList = nucleus.getMeasurementList();
                } else {
                    // Create a new measurement list
                    measurementList = MeasurementListFactory.createMeasurementList(makeMeasurements ? 12 : 0, MeasurementList.TYPE.GENERAL);
                }

                // Add cell shape measurements
                if (makeMeasurements) {
                    ObjectMeasurements.addShapeStatistics(measurementList, r, fpDetection, pathImage.getImage().getCalibration(), "Cell: ");
                    //					ObjectMeasurements.computeShapeStatistics(pathObject, pathImage, fpH, pathImage.getImage().getCalibration());

                    // Add cell measurements
                    for (String key : channelsCell.keySet()) {
                        if (statsMapCell.containsKey(key)) {
                            RunningStatistics stats = statsMapCell.get(key).get(i);
                            measurementList.addMeasurement("Cell: " + key + " mean", stats.getMean());
                            measurementList.addMeasurement("Cell: " + key + " std dev", stats.getStdDev());
                            measurementList.addMeasurement("Cell: " + key + " max", stats.getMax());
                            measurementList.addMeasurement("Cell: " + key + " min", stats.getMin());
                            //						pathObject.addMeasurement("Cytoplasm: " + key + " range", stats.getRange());
                        }
                    }

                    // Add cytoplasm measurements
                    for (String key : channelsCell.keySet()) {
                        if (statsMapCytoplasm.containsKey(key)) {
                            RunningStatistics stats = statsMapCytoplasm.get(key).get(i);
                            measurementList.addMeasurement("Cytoplasm: " + key + " mean", stats.getMean());
                            measurementList.addMeasurement("Cytoplasm: " + key + " std dev", stats.getStdDev());
                            measurementList.addMeasurement("Cytoplasm: " + key + " max", stats.getMax());
                            measurementList.addMeasurement("Cytoplasm: " + key + " min", stats.getMin());
                            //						pathObject.addMeasurement("Cytoplasm: " + key + " range", stats.getRange());
                        }
                    }

                    // Add nucleus area ratio, if available
                    if (nucleus != null && nucleus.getROI() instanceof PathArea) {
                        double nucleusArea = ((PathArea)nucleus.getROI()).getArea();
                        double cellArea = pathROI.getArea();
                        measurementList.addMeasurement("Nucleus/Cell area ratio", Math.min(nucleusArea / cellArea, 1.0));
                        //						measurementList.addMeasurement("Nucleus/Cell expansion", cellArea - nucleusArea);
                    }
                }


                // Create & store the cell object
                PathObject pathObject = new PathCellObject(pathROI, nucleus == null ? null : nucleus.getROI(), null, measurementList);
                pathObjects.add(pathObject);

                roisCellsList.add(r);
            }

    }
    */
}
