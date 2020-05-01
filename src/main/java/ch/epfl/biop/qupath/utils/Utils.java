package ch.epfl.biop.qupath.utils;

import ij.measure.ResultsTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.scripting.QP;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utilities for QuPath scripting that make no use of the GUI
 *
 * @author Olivier Burri
 */
public class Utils extends QP {

    final public static String um = '\u00B5' + "m";
    final private static Logger logger = LoggerFactory.getLogger( Utils.class );

    /**
     * By making use of ObservableMeasurementTableData, we can query each result and get a string back Works, for area,
     * pathclasses, parents, and of course any other measurement in the final table
     *
     * @param resultColumns a list of all the results we want to have, exactly the same names as in teh Measurement
     *                      Results tables
     * @param objects       the pathObjects we want to get the measurements from
     * @param resultsFile   the file where this tool should write to. Note that if the file exists, it will be appended
     * @see ObservableMeasurementTableData
     */
    static public void sendResultsToFile( ArrayList<String> resultColumns, ArrayList<PathObject> objects, File resultsFile ) {

        String delimiter = "\t";

        // We use a ResultsTable to store the data and we need to see if it exists so that we can append to it
        ResultsTable results;

        if ( resultsFile.exists( ) ) {
            // Try to open the previous results table
            try {
                results = ResultsTable.open( resultsFile.getAbsolutePath( ) );
            } catch ( IOException e ) {
                logger.error( "Could not reopen results file {}, either the file is locked or it is not a results table.", resultsFile.getName( ) );
                results = new ResultsTable( );

            }
        } else {
            // New Results Table
            results = new ResultsTable( );
        }

        ObservableMeasurementTableData ob = new ObservableMeasurementTableData( );
        // This line creates all the measurements
        ob.setImageData( getCurrentImageData( ), objects );

        // Get the image Name, as we want to append this to the table
        String rawImageName = new File( getCurrentImageData( ).getServer( ).getPath( ) ).getName( );
        String subImageName = "";
        String imageName = rawImageName;
        // check if it has a subimage
        if ( rawImageName.contains( "::" ) ) {
            String[] splitName = rawImageName.split( "::" );
            imageName = splitName[ 0 ];
            subImageName = splitName[ 1 ];

        }
        // Add value for each selected object
        for ( PathObject pathObject : objects ) {
            results.incrementCounter( );
            results.addValue( "Image Name", imageName );
            results.addValue( "Subimage Name", subImageName );

            // Check if image has associated metadata and add it as columns
            if ( QPEx.getProjectEntry( ).getMetadataKeys( ).size( ) > 0 ) {
                Collection<String> keys = QPEx.getProjectEntry( ).getMetadataKeys( );
                for ( String key : keys ) {
                    results.addValue( "Metadata_" + key, QPEx.getProjectEntry( ).getMetadataValue( key ) );
                }
            }

            // Then we can add the results the user requested
            for ( String col : resultColumns ) {
                String value = ob.getStringValue( pathObject, col );
                if ( ob.isNumericMeasurement( col ) )
                    results.addValue( col.replace( Utils.um, "um" ), ob.getNumericValue( pathObject, col ) );
                if ( ob.isStringMeasurement( col ) )
                    results.addValue( col.replace( Utils.um, "um" ), ob.getStringValue( pathObject, col ) );
            }
        }
        results.save( resultsFile.getAbsolutePath( ) );
        logger.info( "Results {} Saved under {}, contains {} rows", resultsFile.getName( ), resultsFile.getParentFile( ).getAbsolutePath( ), results.size( ) );
    }

    static public void sendResultsToFile( ArrayList<String> resultColumns, ArrayList<PathObject> objects ) {
        File resultsFolder = new File( QuPathGUI.getInstance( ).getProject( ).getPath( ).toFile( ), "results" );
        File resultsFile = new File( resultsFolder, "results.txt" );
        if ( !resultsFolder.exists( ) ) {
            resultsFolder.mkdirs( );
        }
        sendResultsToFile( resultColumns, objects, resultsFile );
    }

    static public void sendResultsToFile( ArrayList<PathObject> objects ) {
        ObservableMeasurementTableData resultColumns = getAllMeasurements( objects );

        sendResultsToFile( resultColumns.getAllNames( ).stream( ).collect( Collectors.toCollection( ArrayList::new ) ), objects );
    }

    /**
     * Returns all the measurements available in QuPath for the all pathObjects Then we can use things like
     * getStringValue() and getDoubleValue()
     *
     * @return a class that you can use to access the results
     * @see ObservableMeasurementTableData
     */
    public static ObservableMeasurementTableData getAllMeasurements( ) {
        PathObjectHierarchy hierarchy = getCurrentHierarchy( );
        return getAllMeasurements( hierarchy.getFlattenedObjectList( null ) );
    }

    /**
     * Creates an ObservableMeasurementTableData for the requested PathObjects
     *
     * @param pathObjects a list of PathObjects to compute measurements from
     * @return an object you can access the results getStringValue() and getDoubleValue()
     * @see ObservableMeasurementTableData
     */
    public static ObservableMeasurementTableData getAllMeasurements( List<PathObject> pathObjects ) {
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData( );
        // This line creates all the measurements
        ob.setImageData( getCurrentImageData( ), pathObjects );
        return ob;
    }

    /**
     * Returns the current pixel size of the active image in microns
     *
     * @return the pixel size in um/px
     */
    public static double getPixelSize( ) {
        return getCurrentImageData( ).getServer( ).getPixelCalibration( ).getAveragedPixelSizeMicrons( );
    }

}