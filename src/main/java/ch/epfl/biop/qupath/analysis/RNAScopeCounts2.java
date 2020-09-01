package ch.epfl.biop.qupath.analysis;

import ij.ImagePlus;
import ij.plugin.filter.MaximumFinder;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.geom.Point2;
import qupath.lib.gui.images.servers.ChannelDisplayTransformServer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.bytedeco.opencv.global.opencv_core.subtract;

/**
 * RNA Scope Counts in Fluorescence or Brightfield
 * <p>
 * Parent object can have detections
 *
 * @author Olivier Burri
 * <p>
 * Inspired from Pete's SubCellularDetection
 * @see qupath.imagej.detect.cells.SubcellularDetection
 */

public class RNAScopeCounts2 extends AbstractInteractivePlugin<BufferedImage> {

    private final static Logger logger = LoggerFactory.getLogger( RNAScopeCounts2.class );
    private static String BRIGHT_DETECTIONS = "Bright Spots";
    private static String DARK_DETECTIONS = "Dark Spots";

    private static List<String> DETECTIONS = Arrays.asList( BRIGHT_DETECTIONS, DARK_DETECTIONS );

    private static int radius = 3; //px

    // Get percentiles
    static double[] percentiles( Mat mat, double... percentiles ) {
        double[] result = new double[ percentiles.length ];
        if ( result.length == 0 )
            return result;
        int n = (int) mat.total( );
        var mat2 = mat.reshape( 1, n );
        var matSorted = new Mat( );
        opencv_core.sort( mat2, matSorted, opencv_core.CV_SORT_ASCENDING + opencv_core.CV_SORT_EVERY_COLUMN );
        try ( var idx = matSorted.createIndexer( ) ) {
            for ( int i = 0; i < result.length; i++ ) {
                long ind = (long) ( percentiles[ i ] / 100.0 * n );
                result[ i ] = idx.getDouble( ind );
            }
        }
        matSorted.release( );
        return result;
    }

    /**
     * Initial version of subcellular detection processing.
     *
     * @param pathObject
     * @param params
     * @param imageData
     * @param minMax
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    static boolean processObject( final PathObject pathObject, final ParameterList params, ImageData<BufferedImage> imageData, double[] minMax ) throws InterruptedException, IOException {

        // Get the base classification for the object as it currently stands
        PathClass baseClass = PathClassTools.getNonIntensityAncestorClass( pathObject.getPathClass( ) );

        // We assume that after this processing, any previous sub-cellular objects should be removed
        pathObject.clearPathObjects( );

        // Ensure we have no existing RNAScope detection measurements - if we do, remove them
        String[] existingMeasurements = pathObject.getMeasurementList( ).getMeasurementNames( ).stream( ).filter( n -> n.startsWith( "RNAScope:" ) ).toArray( n -> new String[ n ] );
        if ( existingMeasurements.length > 0 ) {
            pathObject.getMeasurementList( ).removeMeasurements( existingMeasurements );
            pathObject.getMeasurementList( ).close( );
        }

        ROI pathROI = pathObject.getROI( );
        if ( pathROI == null || pathROI.isEmpty( ) )
            return false;

        ROI cell_roi = pathObject.getROI( );

        ROI nucleus_roi = pathObject instanceof PathCellObject ? ( (PathCellObject) pathObject ).getNucleusROI( ) : null;

        // Get the region
        // Check if we need to invert the image or not
        String detection_type = (String) params.getChoiceParameterValue( "detection_type" );
        String channel = (String) params.getChoiceParameterValue( "channel_to_process" );
        double threshold = params.getDoubleParameterValue( "threshold" ); // Remove this somehow

        int downsample = 1;

        List<ChannelDisplayInfo> allChannels = new ImageDisplay( imageData ).availableChannels( );

        List<ChannelDisplayInfo> rnaScopeChannel = allChannels.stream( ).filter( c -> c.getName( ).contains( channel ) ).limit( 1 ).collect( Collectors.toList( ) );

        ImageServer<BufferedImage> server = ChannelDisplayTransformServer.createColorTransformServer(imageData.getServer( ), rnaScopeChannel);

        RegionRequest cell_request = RegionRequest.createInstance( server.getPath( ), downsample, pathObject.getROI( ) );

        BufferedImage img = null;
        try {
            img = server.readBufferedImage( cell_request );
        } catch ( IOException e ) {
            e.printStackTrace( );
        }


        // Get the top left corner for later adjustments
        double x = cell_request.getX( );
        double y = cell_request.getY( );
        double scaleX = cell_request.getWidth( ) / (double) img.getWidth( );
        double scaleY = cell_request.getHeight( ) / (double) img.getHeight( );

        // Convert to OpenCV Mat
        int width = img.getWidth( );
        int height = img.getHeight( );
        Mat mat_raw = OpenCVTools.imageToMat( img );

        // Invert if looking for dark objects
        if ( detection_type.equals( DARK_DETECTIONS ) ) opencv_core.bitwise_not( mat_raw, mat_raw );

        // Remove noise
        //mat_raw = ImageOps.Filters.median( 1 ).apply( mat_raw );
        //mat_raw = blur( mat_raw, 1.0);

        // SQRT to kill contribution of overly bright spots
        mat_raw = ImageOps.Core.sqrt( ).apply( mat_raw );

        // Normalize input image
        // This is not a minMax normalization as I understand it.
        // This method takes the min and max values of the image to put them as the entered range
        //mat = ImageOps.Normalize.minMax( minMax[ 0 ], minMax[ 1 ] ).apply( mat );

        // Apply Normalization newval = ( oldval - min ) / (max-min)
        Mat mat_norm = ImageOps.Core.subtract( minMax[ 0 ] ).apply( mat_raw );
        mat_norm = ImageOps.Core.divide( minMax[ 1 ] - minMax[ 0 ] ).apply( mat_norm );

        // Perform a background subtraction. We expect sub-diffracted spots, so remove large variations (~15px)
        Mat bg = blur( mat_norm, 25.0 );
        subtract( mat_norm, bg, mat_norm );

        // DoG computation for small spots
        double gaussian_sigma1 = 2.0;
        Mat mat_blur1 = blur( mat_norm, gaussian_sigma1 );

        double gaussian_sigma2 = gaussian_sigma1 * Math.sqrt( 2.0 );
        Mat mat_blur2 = blur( mat_norm, gaussian_sigma2);

        Mat mat_dog = new Mat( );
        subtract( mat_blur1, mat_blur2, mat_dog );

        // Use ImageJ Max Finder with very low prominence
        double prominence = 0.04 + threshold;

        MaximumFinder mf = new MaximumFinder();
        ImagePlus peaks = OpenCVTools.matToImagePlus( "Peaks", mat_dog );
        Polygon maxima = mf.getMaxima( peaks.getProcessor( ), prominence, true );

        // Now assign each point to the cell and to the nucleus
        ArrayList<qupath.lib.geom.Point2> points = new ArrayList<>( );

        ROI area_cell = cell_roi != null && cell_roi.isArea( ) ? cell_roi : null;
        ROI area_nucleus = nucleus_roi != null && nucleus_roi.isArea( ) ? nucleus_roi : null;


        List<PathObject> rnaScopeObjects = new ArrayList<>( );
        int nucleus_counts = 0;

        for ( int c = 0; c < maxima.npoints; c++ ) {
            ROI tempROI = null;
            Point2 p = new Point2( ( maxima.xpoints[c] + 0.5 ) * scaleX + x, ( maxima.ypoints[c] + 0.5 ) * scaleY + y );

            // Check we're inside
            if ( area_cell != null && !area_cell.contains( p.getX( ), p.getY( ) ) )
                continue;

            tempROI = ROIs.createEllipseROI( p.getX( ) - radius, p.getY( ) - radius, radius * 2, radius * 2, ImagePlane.getPlane( cell_roi ) );

            // Found the path object and now check if it is in the nucleus too
            if ( tempROI != null ) {
                if ( area_nucleus != null && !area_nucleus.contains( tempROI.getCentroidX( ), tempROI.getCentroidY( ) ) )
                    nucleus_counts++;
            }

            PathObject pathSubObject = PathObjects.createDetectionObject( tempROI );
           // pathObject.setColorRGB( color );

            rnaScopeObjects.add( pathSubObject );
        }

        /*
        // Apply Adaptive Threshold
        Mat matThresh = ImageOps.Core.multiply( 255 ).apply( mat );
        matThresh.convertTo( matThresh, CV_8UC1 );
        opencv_imgproc.adaptiveThreshold( matThresh, matThresh, 1, opencv_imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, opencv_imgproc.CV_THRESH_BINARY, 5, threshold );

        // Find local maxima
        // Apply max filter to help find maxima
        Mat matMax = new Mat( mat.size( ), mat.type( ) );
        opencv_imgproc.dilate( mat, matMax, new Mat( ) );

        // Apply potential maxima threshold by locating pixels where mat == matMax,
        // i.e. a pixel is equal to the maximum of its 8 neighbours
        // (Note: this doesn’t deal with points of inflection, but with 32-bit this is likely to be rare enough
        // not to be worth the considerably extra computational cost; may need to confirm there are no rounding errors)
        Mat matMaxima = new Mat( );
        compare( mat, matMax, matMaxima, CMP_GE );
        //OpenCVTools.matToImagePlus( "Maxima", matMaxima).show();

        // Only keep the ones that are above the threshold
        min( matThresh, matMaxima, matMaxima );


        // Create path objects from contours
        // This deals with the fact that maxima located within matMaxima (a binary image) aren’t necessarily
        // single pixels, but should be treated as belonging to the same cell
        MatVector contours = new MatVector( );
        Mat temp = new Mat( );
        opencv_imgproc.findContours( matMaxima, contours, temp, opencv_imgproc.RETR_EXTERNAL, opencv_imgproc.CHAIN_APPROX_SIMPLE );
        temp.release( );
        ArrayList<qupath.lib.geom.Point2> points = new ArrayList<>( );

        Shape shape_cell = cell_roi != null && cell_roi.isArea( ) ? RoiTools.getShape( cell_roi ) : null;
        Integer color = ColorTools.makeRGB( 0, 255, 0 );

        ROI area_cell = cell_roi != null && cell_roi.isArea( ) ? cell_roi : null;

        Shape shape_nucleus = nucleus_roi != null && nucleus_roi.isArea( ) ? RoiTools.getShape( nucleus_roi ) : null;
        ROI area_nucleus = nucleus_roi != null && nucleus_roi.isArea( ) ? nucleus_roi : null;


        List<PathObject> rnaScopeObjects = new ArrayList<>( );
        int nucleus_counts = 0;
        for ( long c = 0; c < contours.size( ); c++ ) {
            Mat contour = contours.get( c );

            // Create a polygon ROI
            points.clear( );
            IntIndexer indexerContour = contour.createIndexer( );
            for ( int r = 0; r < indexerContour.size( 0 ); r++ ) {
                int px = indexerContour.get( r, 0L, 0L );
                int py = indexerContour.get( r, 0L, 1L );
                points.add( new qupath.lib.geom.Point2( ( px + 0.5 ) * scaleX + x, ( py + 0.5 ) * scaleY + y ) );
            }

            // Add new polygon if it is contained within the ROI
            ROI tempROI = null;
            if ( points.size( ) == 1 ) {
                qupath.lib.geom.Point2 p = points.get( 0 );
                if ( shape_cell != null && !shape_cell.contains( p.getX( ), p.getY( ) ) ) {
                    continue;
                }

                // Check we're inside
                if ( area_cell != null && !area_cell.contains( p.getX( ), p.getY( ) ) )
                    continue;

                tempROI = ROIs.createEllipseROI( p.getX( ) - radius, p.getY( ) - radius, radius * 2, radius * 2, ImagePlane.getPlane( cell_roi ) );
            } else {
                tempROI = ROIs.createPolygonROI( points, ImagePlane.getPlane( pathObject.getROI( ) ) );
                // Check we're inside
                if ( area_cell != null && !area_cell.contains( tempROI.getCentroidX( ), tempROI.getCentroidY( ) ) )
                    continue;
                tempROI = ROIs.createEllipseROI( tempROI.getCentroidX( ) - radius, tempROI.getCentroidY( ) - radius, radius * 2, radius * 2, ImagePlane.getPlane( cell_roi ) );
            }

            // Found the path object and now check if it is in the nucleus too
            if ( tempROI != null ) {
                if ( area_nucleus != null && !area_nucleus.contains( tempROI.getCentroidX( ), tempROI.getCentroidY( ) ) )
                    nucleus_counts++;
            }

            PathObject pathSubObject = PathObjects.createDetectionObject( tempROI );
            // Check stain2 value at the peak pixel, if required
            pathObject.setColorRGB( color );

            contour.release( );
            rnaScopeObjects.add( pathSubObject );
        }
        */

        //logger.info( "Found " + rnaScopeObjects.size( ) + " contours" );

        // Add measurements
        MeasurementList measurementList = pathObject.getMeasurementList( );
        measurementList.putMeasurement( "RNAScope: " + channel + ": Num spots", rnaScopeObjects.size( ) );
        measurementList.putMeasurement( "RNAScope: " + channel + ": Nucleus Num spots", nucleus_counts );

        // Release matrices
        mat_dog.release( );
        mat_norm.release();
        mat_raw.release();
        mat_blur1.release( );
        mat_blur2.release( );

        pathObject.addPathObjects( rnaScopeObjects );
        return true;
    }

    private static Mat blur( Mat mat, double sigma ) {
        Mat result = new Mat();
        int gaussian_width = (int) ( Math.ceil( sigma * 3 ) * 2 + 1 );

        opencv_imgproc.GaussianBlur( mat, result, new Size( gaussian_width, gaussian_width ), sigma );

        return result;
    }

    @Override
    public ParameterList getDefaultParameterList( final ImageData<BufferedImage> imageData ) {

        ImageServer<BufferedImage> server = imageData.getServer( );

        List<String> channels = null;
        String defaultChannel = null;

        if ( imageData.isBrightfield( ) ) {
            ImageDisplay display = new ImageDisplay( imageData );
            channels = display.availableChannels( ).stream( ).map( channel -> channel.getName( ) ).collect( Collectors.toList( ) );
            ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains( );
            defaultChannel = stains.isH_DAB( ) ? stains.getStain( 2 ).getName( ) : stains.getStain( 0 ).getName( );

        } else {
            channels = imageData.getServer( ).getMetadata( ).getChannels( ).stream( ).map( ch -> ch.getName( ) ).collect( Collectors.toList( ) );
            defaultChannel = channels.get( 0 );
        }

        // Choose detections based on channel type
        String detection_type = imageData.getImageType( ).equals( ImageData.ImageType.FLUORESCENCE ) ? BRIGHT_DETECTIONS : DARK_DETECTIONS;

        ParameterList params = new ParameterList( )
                .addTitleParameter( "RNA Spots Detection" )
                .addChoiceParameter( "channel_to_process", "Channel To Process", defaultChannel, channels )
                .addChoiceParameter( "detection_type", "Type of Detection", detection_type, DETECTIONS )
                .addDoubleParameter( "threshold", "Threshold Offset", 0.0 );

        return params;
    }

    @Override
    public String getName( ) {
        return "RNAScope Counts";
    }

    @Override
    public String getDescription( ) {
        return "Perform a fast count of RNAScope points on the selected channels";
    }

    @Override
    public String getLastResultsDescription( ) {
        return "";
    }

    @Override
    public boolean runPlugin( final PluginRunner<BufferedImage> pluginRunner, final String arg ) {
        boolean success = super.runPlugin( pluginRunner, arg );
        getHierarchy( pluginRunner ).fireHierarchyChangedEvent( this );
        return success;
    }

    @Override
    protected Collection<PathObject> getParentObjects( final PluginRunner<BufferedImage> runner ) {
        Collection<Class<? extends PathObject>> parentClasses = getSupportedParentObjectClasses( );
        List<PathObject> parents = new ArrayList<>( );
        for ( PathObject parent : getHierarchy( runner ).getSelectionModel( ).getSelectedObjects( ) ) {
            for ( Class<? extends PathObject> cls : parentClasses ) {
                if ( cls.isAssignableFrom( parent.getClass( ) ) ) {
                    parents.add( parent );
                    break;
                }
            }
        }
        return parents;
    }

    @Override
    public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses( ) {
        List<Class<? extends PathObject>> parents = new ArrayList<>( );
        parents.add( TMACoreObject.class );
        parents.add( PathAnnotationObject.class );
        parents.add( PathCellObject.class );
        return parents;
    }

    @Override
    protected void addRunnableTasks( final ImageData<BufferedImage> imageData, final PathObject parentObject, List<Runnable> tasks ) {
        final ParameterList params = getParameterList( imageData );
        tasks.add( new ch.epfl.biop.qupath.analysis.RNAScopeCounts2.RNAScopeDetectionRunnable( imageData, parentObject, params ) );
    }

    static class RNAScopeDetectionRunnable implements Runnable {

        private ImageData<BufferedImage> imageData;
        private ParameterList params;
        private PathObject parentObject;

        public RNAScopeDetectionRunnable( final ImageData<BufferedImage> imageData, final PathObject parentObject, final ParameterList params ) {
            this.imageData = imageData;
            this.parentObject = parentObject;
            this.params = params;
        }

        @Override
        public void run( ) {

            String detection_type = (String) params.getChoiceParameterValue( "detection_type" );
            String channel = (String) params.getChoiceParameterValue( "channel_to_process" );

            int downsample = (int) Math.round( Math.max( 1.0, Math.max( imageData.getServer( ).getWidth( ), imageData.getServer( ).getHeight( ) ) / 2048.0 ) );

            List<ChannelDisplayInfo> allChannels = new ImageDisplay( imageData ).availableChannels( );

            logger.info( "Extracting Channel {} with downsample {} for normalization before proceeding", channel, downsample );
            List<ChannelDisplayInfo> rnaScopeChannel = allChannels.stream( ).filter( c -> c.getName( ).contains( channel ) ).limit( 1 ).collect( Collectors.toList( ) );

            logger.info( "All Available Channels: {}", allChannels);
            logger.info( "Channel to process {}, channel {}", channel, rnaScopeChannel );

            ImageServer<BufferedImage> server = ChannelDisplayTransformServer.createColorTransformServer(imageData.getServer( ), rnaScopeChannel);

            RegionRequest global_request = RegionRequest.createInstance( server.getPath( ), downsample, parentObject.getROI( ) );

            BufferedImage img = null;

            try {
                img = server.readBufferedImage( global_request );

                // Convert to OpenCV Mat
                Mat raw = OpenCVTools.imageToMat( img );

                // Invert if looking for dark objects
                if ( detection_type.equals( DARK_DETECTIONS ) ) {
                    logger.info( "    Image will be inverted during processing to detect dark spots" );
                    opencv_core.bitwise_not( raw, raw );
                }

                // Remove noise
                //ImageOps.Filters.median( 2 ).apply( raw );
               // raw = blur( raw, 1.0);

                // SQRT to kill contribution of overly bright spots
                raw = ImageOps.Core.sqrt( ).apply( raw );

                // Compute quantiles for normalization
                double[] minmaxVals = percentiles( raw, 5.0, 99.99 );
                logger.info( "    Min, Max valued for normalization of channel {}: ({})", channel, minmaxVals );

                // Process all cells in the parent object
                if ( parentObject instanceof PathCellObject )
                    processObject( parentObject, params, imageData, minmaxVals );
                else {
                    List<PathObject> cellObjects = PathObjectTools.getFlattenedObjectList( parentObject, null, false ).stream( ).filter( p -> p instanceof PathCellObject ).collect( Collectors.toList( ) );
                    for ( PathObject cell : cellObjects )
                        processObject( cell, params, imageData, minmaxVals );
                }
            } catch ( InterruptedException e ) {
                logger.error( "Processing interrupted", e );
            } catch ( IOException e ) {
                logger.error( "Error processing " + parentObject, e );
            } finally {
                parentObject.getMeasurementList( ).close( );
                imageData = null;
                params = null;
            }
        }

        @Override
        public String toString( ) {
            // TODO: Give a better toString()
            return "RNAScope detection";
        }

    }
}