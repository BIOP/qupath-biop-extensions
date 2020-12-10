package ch.epfl.biop.qupath.utils;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.Roi;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.gui.IJExtension;
import qupath.imagej.tools.IJTools;
import qupath.lib.awt.common.AwtTools;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.images.servers.ChannelDisplayTransformServer;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Here we store a bunch of utility functions that are relevant to QuPath's User interface Like setting colors, lookup
 * tables and such
 */
public class GUIUtils extends QPEx {

    final private static Logger logger = LoggerFactory.getLogger( GUIUtils.class );

    final public static String PROJECT_BASE_DIR = "{%PROJECT}";

    public static ImagePlus getImagePlus( PathObject pathObject, int downsample, boolean includeROI, boolean includeOverlay) {
        return getImagePlus( getCurrentServer(), pathObject, downsample, includeROI, includeOverlay, false, false );
    }

    public static ImagePlus getImagePlus( PathObject pathObject, int downsample) {
        return getImagePlus( getCurrentServer(), pathObject, downsample, true, false, false, false );
    }

    public static ImagePlus getImagePlus( ImageServer server, PathObject pathObject, int downsample, boolean includeROI, boolean includeOverlay, boolean doZ, boolean doT ) {

        QuPathViewer viewer = getCurrentViewer( );
        String unit = server.getPixelCalibration( ).getPixelWidthUnit( );

        // Color transforms are (currently) only applied for brightfield images - for fluorescence we always provide everything as unchanged as possible
        List<ChannelDisplayInfo> selectedChannels = new ArrayList<>( viewer.getImageDisplay( ).selectedChannels( ) );
        List<ChannelDisplayInfo> channels = !selectedChannels.isEmpty( ) ? selectedChannels : null;
        if ( channels != null )
            server = ChannelDisplayTransformServer.createColorTransformServer( server, channels );

        int width, height;
        if ( pathObject == null || !pathObject.hasROI( ) ) {
            width = server.getWidth( );
            height = server.getHeight( );
        } else {
            Rectangle bounds = AwtTools.getBounds( pathObject.getROI( ) );
            width = bounds.width;
            height = bounds.height;
        }

        RegionRequest region;
        ROI roi = pathObject == null ? null : pathObject.getROI( );
        if ( roi == null || PathObjectTools.hasPointROI( pathObject ) ) {
            region = RegionRequest.createInstance( server.getPath( ), downsample, 0, 0, server.getWidth( ), server.getHeight( ), viewer.getZPosition( ), viewer.getTPosition( ) );
        } else
            region = RegionRequest.createInstance( server.getPath( ), downsample, roi );

        // Calculate required z-slices and time-points
        int zStart = doZ ? 0 : region.getZ( );
        int zEnd = doZ ? server.nZSlices( ) : region.getZ( ) + 1;
        int tStart = doT ? 0 : region.getT( );
        int tEnd = doT ? server.nTimepoints( ) : region.getT( ) + 1;
        long nZ = zEnd - zStart;
        long nT = tEnd - tStart;

        int bytesPerPixel = server.isRGB( ) ? 4 : server.getPixelType( ).getBytesPerPixel( ) * server.nChannels( );

        // We should switch to the event dispatch thread when interacting with ImageJ
        try {
            ImagePlus imp;
            PathObjectHierarchy hierarchy = viewer.getHierarchy( );
            OverlayOptions options = viewer.getOverlayOptions( );
            if ( zEnd - zStart > 1 || tEnd - tStart > 1 ) {
                // TODO: Handle overlays
                imp = IJTools.extractHyperstack( server, region, zStart, zEnd, tStart, tEnd );
                if ( includeROI && roi != null ) {
                    Roi roiIJ = IJTools.convertToIJRoi( roi, imp.getCalibration( ), region.getDownsample( ) );
                    imp.setRoi( roiIJ );
                }
                if ( includeOverlay ) {
                    Overlay overlay = new Overlay( );
                    for ( int t = tStart; t < tEnd; t++ ) {
                        for ( int z = zStart; z < zEnd; z++ ) {
                            RegionRequest request2 = RegionRequest.createInstance( region.getPath( ), region.getDownsample( ), region.getX( ), region.getY( ), region.getWidth( ), region.getHeight( ), z, t );
                            var regionPredicate = PathObjectTools.createImageRegionPredicate( request2 );
                            Overlay temp = IJExtension.extractOverlay( hierarchy, request2, options, p -> p != pathObject && regionPredicate.test( p ) );
                            if ( overlay == null )
                                overlay = temp;
                            for ( int i = 0; i < temp.size( ); i++ ) {
                                Roi roiIJ = temp.get( i );
                                roiIJ.setPosition( -1, z + 1, t + 1 );
                                overlay.add( roiIJ );
                            }
                        }
                    }
                    if ( overlay != null && overlay.size( ) > 0 )
                        imp.setOverlay( overlay );
                }
            } else if ( includeOverlay )
                imp = (ImagePlus) IJExtension.extractROIWithOverlay( server, pathObject, hierarchy, region, includeROI, options ).getImage( );
            else
                imp = (ImagePlus) IJExtension.extractROIWithOverlay( server, pathObject, null, region, includeROI, options ).getImage( );

            // Set display ranges if we can
            if ( viewer != null && imp instanceof CompositeImage ) {
                ObservableList<ChannelDisplayInfo> availableChannels = viewer.getImageDisplay().selectedChannels();
                CompositeImage impComp = (CompositeImage) imp;
                if ( availableChannels.size( ) == imp.getNChannels( ) ) {
                    for ( int c = 0; c < availableChannels.size( ); c++ ) {
                        var channel = availableChannels.get( c );
                        imp.setPosition( c + 1, 1, 1 );
                        impComp.setDisplayRange( channel.getMinDisplay( ), channel.getMaxDisplay( ) );
                    }
                    imp.setPosition( 1 );
                }
            } else if ( selectedChannels.size( ) == 1 && imp.getType( ) != ImagePlus.COLOR_RGB ) {
                // Setting the display range for non-RGB images can give unexpected results (changing pixel values)
                var channel = selectedChannels.get( 0 );
                imp.setDisplayRange( channel.getMinDisplay( ), channel.getMaxDisplay( ) );
            }
            return imp;
        } catch ( IOException e ) {
            return null;
        }
    }
    // This replicates the ExtractRegionCommand but makes it more convenient for us to work with it.
    public static ImagePlus getImagePlus( PathObject pathObject, int downsample, boolean includeROI, boolean includeOverlay, boolean doZ, boolean doT ) {

        ImageServer server = getCurrentServer( );
        return getImagePlus( server, pathObject, downsample, includeROI, includeOverlay, doZ, doT);
    }

    public static ImageServer getSelectedChannelsServer( ImageServer currentServer, List<String> channelNames ){

        QuPathViewer viewer = getCurrentViewer( );
        ObservableList<ChannelDisplayInfo> channels = viewer.getImageDisplay( ).availableChannels( );

        // Select the channels
        List <ChannelDisplayInfo> selectedChannels = new ArrayList<>( channelNames.size( ) );
        for ( String chName : channelNames) {
            selectedChannels.addAll( channels.stream( ).filter( c -> c.getName( ).contains( chName ) ).collect( Collectors.toList()) );
        }
            return ChannelDisplayTransformServer.createColorTransformServer(currentServer, selectedChannels);
    }

    /**
     * Gets the current image display or creates one in the case we need it when we work in batch mode
     *
     * @return display the ImageDisplay that matches what we need.
     * @see ImageDisplay
     */
    private static ImageDisplay getCurrentImageDisplay( ) {
        QuPathViewer viewer = getCurrentViewer( );
        ImageData imageData = getCurrentImageData( );
        ImageDisplay display;

        // If we're in batch mode
        if ( isNotBatch( ) ) {
            display = viewer.getImageDisplay( );
        } else {
            display = new ImageDisplay( imageData );
        }
        return display;
    }



    /**
     * Helper that checks if we are in batch mode or not
     *
     * @return false if we are not in batch mode
     */
    private static boolean isNotBatch( ) {
        QuPathViewer viewer = getCurrentViewer( );
        ImageData imageData = getCurrentImageData( );
        return viewer.getImageData( ).equals( imageData );

    }
}