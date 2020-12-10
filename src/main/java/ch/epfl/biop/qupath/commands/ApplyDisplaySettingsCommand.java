package ch.epfl.biop.qupath.commands;

import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import org.controlsfx.dialog.ProgressDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ApplyDisplaySettingsCommand implements Runnable {


    final static Logger logger = LoggerFactory.getLogger( ImageDisplay.class );
    private QuPathGUI qupath;
    private static String title = "Apply current display settings to project";

    //ApplyDisplaySettingsCommand
    public ApplyDisplaySettingsCommand( final QuPathGUI qupath ) {
        if ( !Dialogs.showConfirmDialog( "Apply Brightness And Contrast", "Apply current display settings to all images?\n\nWill apply on images with the same image type and number of channels." ) )
            return;
        this.qupath = qupath;

    }

    public void run( ) {

        final ImageData currentImageData = qupath.getImageData( );
        final int nC = currentImageData.getServer( ).nChannels( );

        logger.info( "Current Image Data: {}", currentImageData );

        final ObservableList<ChannelDisplayInfo> currentChannels = qupath.getViewer( ).getImageDisplay( ).availableChannels( );

        final List<String> channelNames = currentChannels.stream( ).map( c -> c.getName( ) ).collect( Collectors.toList( ) );
        final List<Float> channelMins = currentChannels.stream( ).map( c -> c.getMinDisplay( ) ).collect( Collectors.toList( ) );
        final List<Float> channelMaxs = currentChannels.stream( ).map( c -> c.getMaxDisplay( ) ).collect( Collectors.toList( ) );
        final List<Integer> channelColors = currentChannels.stream( ).map( c -> c.getColor( ) ).collect( Collectors.toList( ) );

        // Get all images from Project
        final List<ProjectImageEntry<BufferedImage>> imageList = qupath.getProject( ).getImageList( );


        Task<Void> worker = new Task<Void>( ) {
            @Override
            protected Void call( ) throws Exception {
                imageList.stream( ).forEach( entry -> {
                    updateMessage( "Trying to apply display settings to " + entry.getImageName( ) + "..." );
                    try {
                        ImageData<BufferedImage> thisImageData = entry.readImageData( );
                        ImageServer server = thisImageData.getServer( );

                        if ( currentImageData.getImageType( ).equals( thisImageData.getImageType( ) ) && nC == server.nChannels( ) ) {
                            ImageDisplay display = new ImageDisplay( thisImageData );
                            ObservableList<ChannelDisplayInfo> displaysChannel = display.availableChannels( );
                            List<ImageChannel> channels = server.getMetadata( ).getChannels( );
                            List<ImageChannel> newChannels = new ArrayList<>( channels );

                            // Iterate through each channel
                            for ( int i = 0; i < nC; i++ ) {
                                // Set the display properly, this saves it as metadata
                                display.setMinMaxDisplay( displaysChannel.get( i ), channelMins.get( i ), channelMaxs.get( i ) );
                                // Need to create new channels with the defined name and color
                                newChannels.set( i, ImageChannel.getInstance( channelNames.get( i ), channelColors.get( i ) ) );
                            }
                            display.saveChannelColorProperties();

                            var metadata = server.getMetadata( );
                            var metadata2 = new ImageServerMetadata.Builder( metadata )
                                    .channels( newChannels )
                                    .build( );

                            thisImageData.updateServerMetadata( metadata2 );

                            logger.info( "Saving Display Settings for Image {}", entry.getImageName( ) );
                            entry.saveImageData( thisImageData );
                        }
                    } catch ( Exception e ) {
                        logger.error( e.getMessage( ), e );
                    }
                } );
                return null;
            }
        };

        ProgressDialog progress = new ProgressDialog( worker );
        progress.setTitle( "Apply display settigns" );
        qupath.submitShortTask( worker );
        progress.showAndWait( );
    }
}