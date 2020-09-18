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
import qupath.lib.scripting.QP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
public class AtlasTools {

    final static Logger logger = LoggerFactory.getLogger( AtlasTools.class);

    private static QuPathGUI qupath = QuPathGUI.getInstance();

    private static String title = "Load ABBA RoiSets from current QuPath project";

    final static private String ALLEN_ONTOLOGY_FILENAME = "AllenMouseBrainOntology.json";
    final static private String ATLAS_ROISET_FILENAME = "ABBA-RoiSet.zip";

    static PathObject getWarpedAtlasRegions( ImageData imageData ) {
       // QuPathViewerPlus viewer = QuPathGUI.getInstance( ).getViewer( );

        List<PathObject> annotations = getFlattenedWarpedAtlasRegions( imageData );

        // Map the ID of the annotation to ease findinf parents
        Map<Integer, PathObject> mappedAnnotations = annotations.stream().collect( Collectors.toMap( e -> (int) ( e.getMeasurementList( ).getMeasurementValue( "Allen ID" ) ), e -> e ) );

        mappedAnnotations.forEach( ( id, annotation ) -> {
            PathObject parent = mappedAnnotations.get( (int) annotation.getMeasurementList( ).getMeasurementValue( "Parent Allen ID" ) );
            if ( parent != null ) {
                parent.addPathObject( annotation );
                //viewer.getOverlayOptions().setPathClassHidden( parent.getPathClass(), true );
            }
        } );

        // Return just the root annotation from Allen Brain, ID 997
        return mappedAnnotations.get( 997 );

    }
    public static List<PathObject> getFlattenedWarpedAtlasRegions( ImageData imageData ) {
        Project project = qupath.getProject( );

        // Get the project folder and get the JSON ontology
        AllenOntologyJson ontology = AllenOntologyJson.getOntologyFromFile( Paths.get( Projects.getBaseDirectory( project ).getAbsolutePath( ), ALLEN_ONTOLOGY_FILENAME ).toFile( ) );

        // Loop through each ImageEntry
        ProjectImageEntry entry = project.getEntry( imageData );

        Path roisetPath = Paths.get( entry.getEntryPath( ).toString( ), ATLAS_ROISET_FILENAME );
        if ( !Files.exists( roisetPath ) ) {
            logger.info( "No RoiSets found in {}", roisetPath );
            return null;
        }

        // Get all the ROIs and add them as PathAnnotations
        List<Roi> rois = RoiSetLoader.openRoiSet( roisetPath.toAbsolutePath( ).toFile( ) );
        logger.info( "Loading {} Allen Regions for {}", rois.size(), entry.getImageName() );

        List<PathObject> annotations = rois.stream( ).map( roi -> {
            // Create the PathObject
            PathObject object = PathObjects.createAnnotationObject( IJTools.convertToROI( roi, 0, 0, 1, null ) );

            // Add metadata to object as acquired from the Ontology
            int object_id = Integer.parseInt( roi.getName( ) );
            // Get associated information
            ch.epfl.biop.atlas.allen.AllenOntologyJson.AllenBrainRegion region = ontology.getRegionFromId( object_id );
            object.setName( region.name );
            object.getMeasurementList( ).putMeasurement( "Allen ID", region.id );
            object.getMeasurementList( ).putMeasurement( "Parent Allen ID", region.parent_structure_id );
            object.setPathClass( QP.getPathClass( region.acronym ) );
            Color c = Color.web( region.color_hex_triplet );
            int color = ColorTools.makeRGB( (int) Math.round( c.getRed( ) * 255 ), (int) Math.round(c.getGreen( ) * 255), (int) Math.round(c.getBlue( ) * 255) );
            object.setColorRGB( color );
            return object;


        } ).collect( Collectors.toList( ) );

        return annotations;
    }

    public static void loadWarpedAtlasAnnotations( ImageData imageData ) {
        imageData.getHierarchy().addPathObject( getWarpedAtlasRegions( imageData ) );
        imageData.getHierarchy().fireHierarchyChangedEvent( AtlasTools.class );
    }

}
