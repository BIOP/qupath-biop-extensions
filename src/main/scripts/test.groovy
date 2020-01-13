//clearAllObjects()
clearDetections()
def regions = getAnnotationObjects()

//def region = PathUtils.getFullImageAnnotation()
//def model = "stardist_default_and_3x10SilviaAnnotations"

//def model = "celine"


def model = "2D_dsb2018"

def downsample = 1
def make_cells = true
def cell_tickness = 3.0
def channel = 1

def sd = new Stardist( model, downsample, make_cells, cell_tickness, channel )

def preprocess = { image ->
    //IJ.run(image, "Median...", "radius=2 slice");
}

regions.each { sd.processRegion( it, preprocess ) }

// All the Stardist Magic is in the Class below


// All imports
import ch.epfl.biop.qupath.utils.*
import ij.IJ
import ij.ImagePlus
import ij.measure.Calibration
import ij.plugin.frame.RoiManager

// To compute image normalization
import org.apache.commons.math3.stat.descriptive.rank.Percentile

// QuPath does not log standard output when declaring Groovy Classes, so we use the logger
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// ROI <> Roi conversion tools
import qupath.imagej.objects.ROIConverterIJ

// Needed when requesting tiles from QuPath
import qupath.lib.geom.ImmutableDimension

// PathObjects
import qupath.lib.objects.*
import qupath.lib.roi.PathROIToolsAwt
import qupath.lib.roi.interfaces.PathArea
import qupath.lib.roi.interfaces.ROI

// Helps create temp directory
import java.nio.file.*


class Stardist {
    boolean make_cells = false
    double cell_radius = 5.0 // um
    String model_name = "2D_dsb2018"

    // Get the QuPath Folder, where StarDist is installed
    File stardist_folder = new File( new File( System.getProperty( "user.dir" ) ).getParent(), "Stardist" )

    int max_region_size = 1024

    int channel

    File python_path, stardist_script_file, model_path

    // Default overlap, required in pixels by QuPath
    int overlap = 20 //px
    int downsample = 1

    // Logger to see everything the script does
    final static private Logger logger = LoggerFactory.getLogger( Stardist.class );

    Stardist( String model_name, int downsample, boolean make_cells, double cell_radius, int channel ) {

        // Check if we want to make cells
        this.make_cells = make_cells

        this.cell_radius = cell_radius

        // Get the default paths and initialize StarDist
        this.python_path = new File( this.stardist_folder, "Scripts/python" )

        this.stardist_script_file = new File( stardist_folder, "stardist_ij.py" )

        this.model_path = new File( stardist_folder, "models" )

        this.downsample = downsample

        this.model_name = model_name

        this.channel = channel
    }

    // This processes the given region, at the desired downsampling and with the chosen code for preprocessing
    // Code should take an ImagePlus as input argument and return the same ImagePlus
    void processRegion( PathObject region, Closure preprocess ) {

        // Current project directory to store temp data
        def current_project_stardist_dir = new File( getQuPath().getCurrentProjectDirectory(), "Stardist" )

        logger.info( "Storing Temporary images in {}", current_project_stardist_dir )

        current_project_stardist_dir.mkdirs()

        def temp_folder = Files.createTempDirectory( current_project_stardist_dir.toPath(), "Temp" );

        def data_folder = temp_folder.toFile()

        // Stardist will store the resulting Roisets in 'rois'
        def roiset_path = new File( data_folder, 'rois' )

        // We need this when requesting tiles.
        def dims = new ImmutableDimension( max_region_size, max_region_size )

        // Make tiles as needed 
        def regions = PathROIToolsAwt.computeTiledROIs( region.getROI(), dims, dims, true, this.overlap ).collect {
            return new PathAnnotationObject( it )
        }

        // Show the regions that are being processed
        addObjects( regions )
        fireHierarchyUpdate()

        // Compute global min-max normalization values to use here on the full region. Downsample if the image is tiled
        def downsample_corr = 1.0
        if ( regions.size() > 1 ) {
            downsample_corr = 4.0
        }

        logger.info( "Computing Min and Max values for data normalization on {}x downsampled data", this.downsample * downsample_corr as int )
        // Compute on the whole image

        def full_image = GUIUtils.getImagePlus( region, this.downsample * downsample_corr as int, false, false, ["Channel " + this.channel] )
        def min_max = getQuantileMinMax( full_image, 1.0, 99.8 )

        logger.info( "Min: {}, Max: {}", min_max["min"], min_max["max"] )

        full_image.close()


        // Save all the regions to the temp folder, and keep their information for when we pick up the Rois later
        def stardist_output = regions.withIndex().collect { r, idx ->

            ImagePlus imp = GUIUtils.getImagePlus( r, this.downsample, false, false, ["Channel " + this.channel] )

            def cal = imp.getCalibration()

            // Preprocessing closure, unless null
            preprocess( imp )

            File imageName = new File( data_folder, "region" + IJ.pad( idx, 3 ) )


            IJ.saveAsTiff( imp, imageName.getAbsolutePath() )
            logger.info( "Saved Image {}.tif", imageName.getName() )

            imp.close()

            // Return a List with the image name, the region and the calibration ( To get the ROIs properly later )
            return [name: imageName.getName(), region: r, calibration: cal]
        }

        // Call Stardist, this is the magic line
        runStarDist( data_folder, min_max )

        //def execute_string = this.python_path.getAbsolutePath() + ' "' + this.stardist_script_file.getAbsolutePath() + '" "' + this.data_folder.getAbsolutePath() + '" "' + this.model_path.getAbsolutePath() + '" ' + this.model_name + ' ' + min_max['min'] + ' ' + min_max['max']

        // Get ROI Set(s) and import
        def rm = RoiManager.getRoiManager()

        // all_rois is a list of PathDetectionObjects, that way they are in the right position and possibly cells already
        def all_detections = []
        stardist_output.each { namecal ->

            def roi_file = new File( roiset_path, namecal["name"] + ".tif_rois.zip" )

            def cal = namecal["calibration"]

            def current_region = namecal["region"]

            if ( roi_file.exists() ) {
                logger.info( "Image {}.tif had a RoiSet", namecal["name"] )

                rm.reset()
                rm.runCommand( "Open", roi_file.getAbsolutePath() )

                def rois = rm.getRoisAsArray() as List

                def detections = rois.collect {
                    def roi = ROIConverterIJ.convertToPathROI( it, cal, downsample, -1, 0, 0 )
                    return new PathDetectionObject( roi )
                }

                if ( this.make_cells ) {
                    logger.info( "Creating Cells from {} Detections...", detections.size() )

                    def cell_detections = PathUtils.createCellObjects( current_region, detections, cell_radius, downsample * 2 )
                    all_detections.addAll( cell_detections )

                } else {
                    logger.info( "Adding {} detections", detections.size() )
                    all_detections.addAll( detections )
                }
            }

        }

        if ( regions.size() > 1 ) {
            logger.info( "Removing overlapping objects" )
            // Find the detections that may need to be removed

            def overlaping_detections = getOverlapingDetetections( regions, all_detections )

            // Remove overlap and add the ones to keep again after
            all_detections.removeAll( overlaping_detections )

            // Do some filtering to avoid issues where there is overlap
            def kept_detections = filterRoiByOverlap( overlaping_detections, 50 )

            // Add regions again
            all_detections.addAll( kept_detections )
        }

        region.addPathObjects( all_detections )

        region.removePathObjects( regions )

        fireHierarchyUpdate()

        temp_folder.toFile().deleteDir()

        logger.info( "Done" )

    }

    def getOverlapingDetetections( def regions, def all_detections ) {

        // Get all overlap regions
        def overlap_regions = []
        regions.each { r1 ->
            regions.each { r2 ->
                if ( r1 != r2 ) {
                    // check overlap
                    def merge = PathROIToolsAwt.combineROIs( (PathArea) r1.getROI(), (PathArea) r2.getROI(), PathROIToolsAwt.CombineOp.INTERSECT )
                    if ( !merge.isEmpty() ) {
                        // Make into an annotation that represents the overlap
                        overlap_regions.add( new PathAnnotationObject( merge ) )
                    }
                }
            }
        }

        // Combine all now
        def merged = mergeAnnotations( overlap_regions )
        removeObject( merged, true )

        // Find all annotations that are touching somehow this region, avoid shapes as they are slow
        def overlap_detections = all_detections.findAll {
            def roi = it.getROI()

            def x1 = roi.getCentroidX()
            def y1 = roi.getCentroidY()

            def x2 = roi.getBoundsX()
            def y2 = roi.getBoundsY()

            def x3 = roi.getBoundsX() + roi.getBoundsWidth()
            def y3 = roi.getBoundsY()

            def x4 = roi.getBoundsX()
            def y4 = roi.getBoundsY() + roi.getBoundsHeight()


            def x5 = roi.getBoundsX() + roi.getBoundsWidth()
            def y5 = roi.getBoundsY() + roi.getBoundsHeight()

            return merged.getROI().contains( x1, y1 ) || merged.getROI().contains( x2, y2 ) || merged.getROI().contains( x3, y3 ) || merged.getROI().contains( x4, y4 ) || merged.getROI().contains( x5, y5 )
        }

        // From here get a hashmap of the regions when their bounding boxes match
        def temp_overlap_detections = overlap_detections.clone()

        def detections_to_check = [:]
        overlap_detections.each { det ->
            temp_overlap_detections.remove( det )
            def det_candidates = temp_overlap_detections.collect { det1 ->
                if ( hasBBOverlap( det, det1 ) ) {
                    return det1
                }
                return []
            }.flatten()

            detections_to_check.put( det, det_candidates )
        }

        logger.info( "There are {} detections that potentially overlap", overlap_detections.size() )

        return overlap_detections
    }

    boolean hasBBOverlap( def po1, def po2 ) {
        ROI r1 = po1.getROI()

        double r1_left = r1.getBoundsX()
        double r1_top = r1.getBoundsY()
        double r1_right = r1.getBoundsWidth() + r1_left
        double r1_bottom = r1.getBoundsHeight() + r1_top

        ROI r2 = po2.getROI()

        double r2_left = r2.getBoundsX()
        double r2_top = r2.getBoundsY()
        double r2_right = r2.getBoundsWidth() + r2_left
        double r2_bottom = r2.getBoundsHeight() + r2_top

        return !( r2_left > r1_right
                || r2_right < r1_left
                || r2_top > r1_bottom
                || r2_bottom < r1_top )
    }

    // "percentOverlap" : compare the percent overlap of each roi.Areas,
    //  and delete the roi with the largent percentage (most probably included within the other).
    def getRoisToDetleteByOverlap( def roiMap, def percent_overlap_lim ) {

        logger.info( "Overlap Filter: Overlap Limit {}%", percent_overlap_lim )

        def roisToDelete = []

        roiMap.each { rA, candidates ->
            logger.info( "{}", rA )
            candidates.each { rB ->
                def roiA = rA.getROI()
                def roiB = rB.getROI()

                def merge = PathROIToolsAwt.combineROIs( (PathArea) roiA, (PathArea) roiB, PathROIToolsAwt.CombineOp.INTERSECT )

                if ( merge.isEmpty() ) return

                def roiA_ratio = merge.getArea() / roiA.getArea() * 100
                def roiB_ratio = merge.getArea() / roiB.getArea() * 100

                if ( ( roiA_ratio > percent_overlap_lim ) || ( roiB_ratio > percent_overlap_lim ) )
                    ( roiA_ratio < roiB_ratio ) ? roisToDelete.add( rB ) : roisToDelete.add( rA )
            }
        }

        logger.info( "{} overlapping detections to be removed", roisToDelete.size() )

        return roisToDelete
    }

    // Get get quantile values for normalization
    def getQuantileMinMax( ImagePlus image, double lower_q, double upper_q ) {
        logger.info( "Using {}% lower quantile and {}% upper quantile", lower_q, upper_q )
        def proc = image.getProcessor().convertToFloatProcessor()
        def perc = new Percentile()

        def lower_val = perc.evaluate( proc.getPixels() as double[], lower_q )
        def upper_val = perc.evaluate( proc.getPixels() as double[], upper_q )

        return [min: lower_val, max: upper_val]
    }

    public void runStarDist( File data_folder, def min_max ) {
        logger.info( "Running Stardist" )

        def sout = new StringBuilder()
        def serr = new StringBuilder()

        def process = new ProcessBuilder( this.python_path.getAbsolutePath(),
                this.stardist_script_file.getAbsolutePath(),
                data_folder.getAbsolutePath(),
                this.model_path.getAbsolutePath(),
                this.model_name,
                min_max['min'] as String,
                min_max['max'] as String )
                .redirectErrorStream( true )
                .start()

        logger.info( "Executed Stardist command: {}", process );

        process.consumeProcessOutput( sout, serr )

        // Show what is happening in the log
        while ( process.isAlive() ) {

            if ( sout.size() > 0 ) {
                logger.info( sout.toString() )
                sout.setLength( 0 )
            }

            if ( serr.length() > 0 ) {
                logger.info( serr.toString() )
                serr.setLength( 0 )
            }

            sleep( 200 )

        }

        logger.info( "Running Stardist Complete" )
    }

}