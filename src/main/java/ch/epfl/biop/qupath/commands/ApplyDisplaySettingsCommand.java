package ch.epfl.biop.qupath.commands;

import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.plugins.PluginRunnerFX;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.plugins.SimpleProgressMonitor;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.stream.Collectors;

public class ApplyDisplaySettingsCommand implements Runnable {


    final static Logger logger = LoggerFactory.getLogger(ImageDisplay.class);
    private QuPathGUI qupath;
    private static String title = "Apply current display settings to project";

    //ApplyDisplaySettingsCommand
    public ApplyDisplaySettingsCommand(final QuPathGUI qupath) {
        if (!Dialogs.showConfirmDialog("Apply Brightness And Contrast", "Apply current display settings to all images?\n\nWill apply on images with the same image type and number of channels."))
            return;
        this.qupath = qupath;

    }

    public void run() {

        ImageData currentImageData = qupath.getImageData();


        ImageServer currentServer = currentImageData.getServer();

        ObservableList<ChannelDisplayInfo> currentChannels = qupath.getViewer( ).getImageDisplay( ).availableChannels( );

        List<String> channel_names = currentChannels.stream( ).map( c -> c.getName( ) ).collect( Collectors.toList( ) );
        List<Float> channel_min =currentChannels.stream( ).map( c -> c.getMinDisplay( ) ).collect( Collectors.toList( ) );
        List<Float> channel_max = currentChannels.stream( ).map( c -> c.getMaxDisplay( ) ).collect( Collectors.toList( ) );
        List<Integer> channel_colors = currentChannels.stream( ).map( c -> c.getColor( ) ).collect( Collectors.toList( ) );

        // Get all images from Project
        List<ProjectImageEntry<BufferedImage>> imageList = qupath.getProject().getImageList();
        SimpleProgressMonitor progress = new PluginRunnerFX( qupath ).makeProgressMonitor( );

        progress.startMonitoring( "Applying Current Display Settings", imageList.size(), false);
        imageList.parallelStream().forEach(entry -> {
            ImageData<BufferedImage> imageData = null;
            try {
                progress.updateProgress(1, entry.getImageName() , null);
                imageData = entry.readImageData();


                ImageServer server = entry.getServerBuilder().build();

                if (imageData == null) imageData = qupath.createNewImageData(server, true);
                if (currentImageData.getImageType().equals(imageData.getImageType()) && currentServer.getMetadata().getSizeC() == server.getMetadata().getSizeC()) {

                    QP.setChannelColors( imageData, channel_colors.toArray( new Integer[0] ) );
                    QP.setChannelNames( imageData, channel_names.toArray( new String[0] ) );

                    for( int i=0; i<channel_min.size(); i++ ) {
                        QPEx.setChannelDisplayRange( imageData, channel_names.get( i ), channel_min.get( i ), channel_max.get( i ) );

                    }
                    logger.info("Saving Display Settings for Image {}", entry.getImageName());
                    entry.saveImageData(imageData);
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        progress.pluginCompleted( "Done" );
    }

}