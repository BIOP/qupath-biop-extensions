package ch.epfl.biop.qupath.utils;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Calibration;
import ij.plugin.filter.EDM;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.detect.cells.ObjectMeasurements;
import qupath.imagej.processing.RoiLabeling;
import qupath.imagej.processing.Watershed;
import qupath.imagej.tools.IJTools;
import qupath.imagej.tools.PixelImageIJ;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.stats.RunningStatistics;
import qupath.lib.analysis.stats.StatisticsHelper;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.StainVector;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.measurements.MeasurementListFactory;
import qupath.lib.objects.*;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.*;
import java.awt.geom.Area;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class PathUtils extends QP {

    // Call a logger so we can write to QuPath's log windows as needed
    private final static Logger logger = LoggerFactory.getLogger( PathUtils.class );

    /**
     * returns a rectangle with teh whole dataset as an annotation.
     * It does not add it to the Hierarchy
     * @return an Annotation Object with the whole image
     */
    public static PathObject getFullImageAnnotation( ) {
        ImageData<?> imageData = getCurrentImageData( );
        if ( imageData == null )
            return null;
        ImageServer server = imageData.getServer( );
        PathObject pathObject = PathObjects.createAnnotationObject( ROIs.createRectangleROI( 0, 0, server.getWidth( ), server.getHeight( ), null ) );
        return pathObject;
    }

    /**
     * returns the area of the current PathObject in calibrated units.
     *
     * @param object the object to try and compute the area from
     * @return the calibrated area in um2
     */
    public static double getAreaMicrons( PathObject object ) {
        double pixel_size = Utils.getPixelSize( );
        Double area = getArea( object );
        return area * pixel_size * pixel_size;
    }

    /**
     * returns the area of the current PathObject in pixels If the area is not defined (like points) it returns 0
     *
     * @param object the object to try and compute the area from
     * @return the area in px2
     */
    public static double getArea( PathObject object ) {
        ROI roi = object.getROI( );
        return roi.getArea();
    }

    /**
     * Splits the defined pathobject using the provided splitter. it's basically a subtract but NOTE that this method
     * will not return any shapes with holes, so please be aware of this limitation objects that end up separated become
     * new PathObjects
     *
     * @param pathObject the object that will be split.
     * @param splitter   the object used for splitting
     * @return a List of PathObject with the resulting Paths
     */
    public static List<PathObject> splitObject( PathObject pathObject, PathObject splitter ) {

        // Convert the line to an Area, so we can use combineROIs
        ROI area = splitter.getROI( ) instanceof LineROI ? LineToArea( splitter.getROI( ), 2 ) : splitter.getROI( );

        ROI splitObject = RoiTools.combineROIs( pathObject.getROI( ), area, RoiTools.CombineOp.SUBTRACT );

        // This method, by Pete, separates the areas into separate polygons
        PolygonROI[][] split = RoiTools.splitAreaToPolygons( RoiTools.getArea( splitObject ), area.getC(), area.getZ(), area.getT() );


        List<PathObject> objects = new ArrayList<>( split[ 1 ].length );
        for ( int i = 0; i < split[ 1 ].length; i++ ) {
            objects.add( PathObjects.createAnnotationObject( split[ 1 ][ i ] ) );
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
    public static ROI LineToArea( ROI line, double thickness ) {
        if ( line instanceof LineROI ) {
            // Get the points, offset them 90 degrees
            double px1 = ( (LineROI) line ).getX1( );
            double px2 = ( (LineROI) line ).getX2( );
            double py1 = ( (LineROI) line ).getY1( );
            double py2 = ( (LineROI) line ).getY2( );

            double dx = px2 - px1;
            double dy = py2 - py1;

            double norm = Math.sqrt( dx * dx + dy * dy );

            double normalx = -1 * dy / norm;
            double normaly = dx / norm;
            // define 4 points separated by thickness
            ArrayList<Point2> points = new ArrayList<Point2>( 4 );
            double p1x = px1 + normalx * thickness / 2;
            double p1y = py1 + normaly * thickness / 2;
            points.add( new Point2( p1x, p1y ) );

            double p2x = px1 - normalx * thickness / 2;
            double p2y = py1 - normaly * thickness / 2;
            points.add( new Point2( p2x, p2y ) );

            double p3x = px2 - normalx * thickness / 2;
            double p3y = py2 - normaly * thickness / 2;
            points.add( new Point2( p3x, p3y ) );

            double p4x = px2 + normalx * thickness / 2;
            double p4y = py2 + normaly * thickness / 2;
            points.add( new Point2( p4x, p4y ) );

            ROI result = ROIs.createPolygonROI( points, null );
            PathObject def = PathObjects.createDetectionObject( result );

            return result;
        }
        return null;
    }

    public static List<PathObject> createCellObjects( PathObject parent, List<PathObject> objects, double thickness_um ) {

        ImagePlane plane = parent.getROI().getImagePlane();

        int c = parent.getROI( ).getC( );
        int z = parent.getROI( ).getZ( );
        int t = parent.getROI( ).getT( );

        ImagePlus image = GUIUtils.getImagePlus( parent, 1, false, false );

        ImageProcessor ip = image.getProcessor( );

        int thickness_px = (int) Math.round( image.getCalibration( ).getRawX( thickness_um ) );

        // We need to create the channels for the measurements
        ColorDeconvolutionStains stains = getCurrentImageData( ).getColorDeconvolutionStains( );
        Map<String, FloatProcessor> channels = new LinkedHashMap<>( );
        Map<String, FloatProcessor> channelsCell = new LinkedHashMap<>( );

        if ( ip instanceof ColorProcessor && stains != null ) {
            FloatProcessor[] fps = IJTools.colorDeconvolve( (ColorProcessor) ip, stains );
            for ( int i = 0; i < 3; i++ ) {
                StainVector stain = stains.getStain( i + 1 );
                if ( !stain.isResidual( ) ) {
                    channels.put( stain.getName( ) + " OD", fps[ i ] );
                    channelsCell.put( stain.getName( ) + " OD", fps[ i ] );
                }
            }
        }
        if ( ip instanceof ColorProcessor ) {
            channels.put( "Channel 1", ( (ColorProcessor) ip ).toFloat( 0, null ) );
            channels.put( "Channel 2", ( (ColorProcessor) ip ).toFloat( 1, null ) );
            channels.put( "Channel 3", ( (ColorProcessor) ip ).toFloat( 2, null ) );
        } else {
            for ( int ca = 1; ca <= image.getNChannels( ); ca++ ) {
                channels.put( "Channel " + ca, image.getStack( ).getProcessor( image.getStackIndex( ca, 0, 0 ) ).convertToFloatProcessor( ) );
            }
        }
        // Measure in all channels for the cells
        channelsCell.putAll( channels );


        // Need to create a mask image from this, slightly larget to make sure all ROIs can be enlarged

        ImagePlus labels = IJ.createImage( "Mask", image.getWidth( ), image.getHeight( ), 1, 16 );

        Calibration cal = new Calibration( );

        labels.setCalibration( cal );
        ImageProcessor ipLabels = labels.getProcessor( );

        List<Roi> roisNuclei = new ArrayList<>( objects.size( ) );

        int id = 1;
        for ( PathObject object : objects ) {

            Shape shape = RoiTools.getShape( object.getROI( ) );
            Roi r = new ShapeRoi( shape );
            roisNuclei.add( r );
            ipLabels.setValue( id );
            ipLabels.fill( r );
            id++;
        }

        ImageProcessor bp = ipLabels.duplicate( );
        bp.setThreshold( 1, 65535, ImageProcessor.NO_LUT_UPDATE );
        bp = bp.createMask( );

        FloatProcessor fpEDM = new EDM( ).makeFloatEDM( bp, (byte) 255, false );
        fpEDM.multiply( -1 );
        // Need to create an ImagePlus with all the PathObjects
        // Create cell ROIs
        ImageProcessor ipLabelsCells = ipLabels.duplicate( );
        Watershed.doWatershed( fpEDM, ipLabelsCells, -thickness_px, false );

        PolygonRoi[] roisCells = RoiLabeling.labelsToFilledROIs( ipLabelsCells, objects.size( ) );

        // Measure nuclei for all required channels
        Map<String, List<RunningStatistics>> statsMap = new LinkedHashMap<>( );

        SimpleImage imgLabels = new PixelImageIJ( ipLabels );
        for ( String key : channels.keySet( ) ) {
            List<RunningStatistics> statsList = StatisticsHelper.createRunningStatisticsList( objects.size( ) );
            StatisticsHelper.computeRunningStatistics( new PixelImageIJ( channels.get( key ) ), imgLabels, statsList );
            statsMap.put( key, statsList );
        }

        // Create cell objects
        List<PathObject> pathObjects = new ArrayList<>( );

        for ( int i = 0; i < objects.size( ); i++ ) {
            PolygonRoi cellRoi = roisCells[ i ];
            Roi nucRoi = roisNuclei.get( i );
            if ( cellRoi == null )
                continue;
            cellRoi = new PolygonRoi( cellRoi.getInterpolatedPolygon( Math.min( 2.5, cellRoi.getNCoordinates( ) * 0.1 ), false ), Roi.POLYGON );

            PolygonROI pathROI = IJTools.convertToPolygonROI( cellRoi, labels.getCalibration( ), 1, plane );
            pathROI = ShapeSimplifier.simplifyPolygon( pathROI, 1 / 4.0 );

            // Create a new shared measurement list for the nuclei
            MeasurementList measurementList = MeasurementListFactory.createMeasurementList( 30, MeasurementList.MeasurementListType.FLOAT );

            ObjectMeasurements.addShapeStatistics( measurementList, nucRoi, image.getProcessor( ), cal, "Nucleus: " );

            for ( String key : channels.keySet( ) ) {
                List<RunningStatistics> statsList = statsMap.get( key );
                RunningStatistics stats = statsList.get( i );
                measurementList.addMeasurement( "Nucleus: " + key + " mean", stats.getMean( ) );
                measurementList.addMeasurement( "Nucleus: " + key + " sum", stats.getSum( ) );
                measurementList.addMeasurement( "Nucleus: " + key + " std dev", stats.getStdDev( ) );
                measurementList.addMeasurement( "Nucleus: " + key + " max", stats.getMax( ) );
                measurementList.addMeasurement( "Nucleus: " + key + " min", stats.getMin( ) );
                measurementList.addMeasurement( "Nucleus: " + key + " range", stats.getRange( ) );
            }


            // Compute cell DAB stats
            Map<String, List<RunningStatistics>> statsMapCell = new LinkedHashMap<>( );
            for ( String key : channelsCell.keySet( ) ) {
                List<RunningStatistics> statsList = StatisticsHelper.createRunningStatisticsList( roisNuclei.size( ) );
                StatisticsHelper.computeRunningStatistics( new PixelImageIJ( channelsCell.get( key ) ), new PixelImageIJ( ipLabelsCells ), statsList );
                statsMapCell.put( key, statsList );
            }


            // Create labelled image for cytoplasm, i.e. remove all nucleus pixels
            for ( int k = 0; k < ipLabels.getWidth( ) * ipLabels.getHeight( ); k++ ) {
                if ( ipLabels.getf( k ) != 0 )
                    ipLabelsCells.setf( k, 0f );
            }

            // Compute cytoplasm stats
            Map<String, List<RunningStatistics>> statsMapCytoplasm = new LinkedHashMap<>( );
            for ( String key : channelsCell.keySet( ) ) {
                List<RunningStatistics> statsList = StatisticsHelper.createRunningStatisticsList( roisNuclei.size( ) );
                StatisticsHelper.computeRunningStatistics( new PixelImageIJ( channelsCell.get( key ) ), new PixelImageIJ( ipLabelsCells ), statsList );
                statsMapCytoplasm.put( key, statsList );
            }

            ObjectMeasurements.addShapeStatistics( measurementList, cellRoi, image.getProcessor(), image.getCalibration( ), "Cell: " );

            // Add cell measurements
            for ( String key : channelsCell.keySet( ) ) {
                if ( statsMapCell.containsKey( key ) ) {
                    RunningStatistics stats = statsMapCell.get( key ).get( i );
                    measurementList.addMeasurement( "Cell: " + key + " mean", stats.getMean( ) );
                    measurementList.addMeasurement( "Cell: " + key + " std dev", stats.getStdDev( ) );
                    measurementList.addMeasurement( "Cell: " + key + " max", stats.getMax( ) );
                    measurementList.addMeasurement( "Cell: " + key + " min", stats.getMin( ) );
                    //						pathObject.addMeasurement("Cytoplasm: " + key + " range", stats.getRange());
                }
            }

            // Add cytoplasm measurements
            for ( String key : channelsCell.keySet( ) ) {
                if ( statsMapCytoplasm.containsKey( key ) ) {
                    RunningStatistics stats = statsMapCytoplasm.get( key ).get( i );
                    measurementList.addMeasurement( "Cytoplasm: " + key + " mean", stats.getMean( ) );
                    measurementList.addMeasurement( "Cytoplasm: " + key + " std dev", stats.getStdDev( ) );
                    measurementList.addMeasurement( "Cytoplasm: " + key + " max", stats.getMax( ) );
                    measurementList.addMeasurement( "Cytoplasm: " + key + " min", stats.getMin( ) );
                    //						pathObject.addMeasurement("Cytoplasm: " + key + " range", stats.getRange());
                }
            }

            // Add nucleus area ratio, if available
            double nucleusArea =  objects.get( i ).getROI( ).getArea( );
            double cellArea = pathROI.getArea( );
            measurementList.addMeasurement( "Nucleus/Cell area ratio", Math.min( nucleusArea / cellArea, 1.0 ) );



            PathObject pathObject = PathObjects.createCellObject( pathROI, objects.get( i ).getROI( ), null, measurementList );
            pathObjects.add( pathObject );

        }

        // Close the measurement lists
        for (PathObject pathObject : pathObjects)
            pathObject.getMeasurementList().close();


        return pathObjects;


    }

    /**
     * This method tries to merge all the pathobjects that are touching, but keeping all others intact
     *
     * @param objects
     * @return
     */
    public static List<PathObject> mergeTouchingPathObjects( List<PathObject> objects ) {
        logger.info( "Merging Touching objects from list with {} elements", objects.size( ) );
        List<HashSet<PathObject>> candidates = objects.parallelStream( ).map( ob1 -> {
            // First check if the bounding boxes touch, which will define those that are worth doing all the mess for
            Area s1 = RoiTools.getArea( ob1.getROI( ) );

            HashSet<PathObject> touching = objects.parallelStream( ).filter( ob2 -> {
                if ( boundsOverlap( ob1, ob2 ) ) {

                    Area s2 = RoiTools.getArea( ob2.getROI( ) );
                    s2.intersect( s1 );
                    return !s2.isEmpty( );

                } else {
                    return false;
                }
            } ).collect( Collectors.toCollection( HashSet::new ) );

            return touching;
        } ).collect( Collectors.toList( ) );

        logger.info( "Looking for candidate merges done {} candidates", candidates.size( ) );

        logger.info( "Removing single objects..." );

        // Need to see if any element in each list matches, if that's the case we add them all to the first list and remove the older lis
        // remove all the ones that are alone, we do not touch these
        List<HashSet<PathObject>> forSort = candidates.stream( ).filter( touching -> touching.size( ) > 1 ).collect( Collectors.toList( ) );

        List<HashSet<PathObject>> untouched = candidates.stream( ).filter( touching -> touching.size( ) == 1 ).collect( Collectors.toList( ) );

        logger.info( "Removing single candidates done: {} candidates left", forSort.size( ) );

// Go through it checking that there are no duplicates and remove them if any
        for ( int i = forSort.size( ) - 1; i >= 0; i-- ) {
            for ( int j = i - 1; j >= 0; j-- ) {
                int finalJ = j;
                if ( forSort.get( i ).stream( ).anyMatch( forSort.get( finalJ )::contains ) ) {
                    forSort.get( i ).addAll( forSort.get( j ) );
                    forSort.remove( forSort.get( j ) );
                    i--;
                }
            }
        }

        forSort.addAll( untouched );

        List<PathObject> result = forSort.stream( ).map( candidate -> mergePathObjects( new ArrayList<PathObject>( candidate ) ) ).collect( Collectors.toList( ) );

        return result;
    }


    public static PathObject mergePathObjects( List<PathObject> pathobjects ) {
        // Get all the selected annotations with area
        ROI shapeNew = null;
        List<PathObject> children = new ArrayList<>( );
        for ( PathObject child : pathobjects ) {
                if ( shapeNew == null )
                    shapeNew = child.getROI( );//.duplicate();
                else
                    shapeNew = RoiTools.combineROIs( shapeNew, child.getROI( ), RoiTools.CombineOp.ADD );
                children.add( child );
            }
        // Check if we actually merged anything
        if ( children.isEmpty( ) )
            return null;
        if ( children.size( ) == 1 )
            return children.get( 0 );

        // Create and add the new object, removing the old ones

        PathObject pathObjectNew = null;

        if ( pathobjects.get( 0 ) instanceof PathDetectionObject ) {
            pathObjectNew = PathObjects.createDetectionObject( shapeNew );
        } else {
            pathObjectNew = PathObjects.createAnnotationObject( shapeNew );
        }

        return pathObjectNew;
    }

    private static boolean boundsOverlap( PathObject ob1, PathObject ob2 ) {
        double x11 = ob1.getROI( ).getBoundsX( );
        double y11 = ob1.getROI( ).getBoundsY( );
        double x12 = x11 + ob1.getROI( ).getBoundsWidth( );
        double y12 = y11 + ob1.getROI( ).getBoundsHeight( );

        double x21 = ob2.getROI( ).getBoundsX( );
        double y21 = ob2.getROI( ).getBoundsY( );
        double x22 = x21 + ob2.getROI( ).getBoundsWidth( );
        double y22 = y21 + ob2.getROI( ).getBoundsHeight( );

        return x12 >= x21 && x22 >= x11 && y12 >= y21 && y22 >= y11;

    }
}

