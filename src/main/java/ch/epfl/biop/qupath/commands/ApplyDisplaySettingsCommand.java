package ch.epfl.biop.qupath.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

public class ApplyDisplaySettingsCommand implements PathCommand {

    final static Logger logger = LoggerFactory.getLogger(ImageDisplay.class);
    private final QuPathGUI qupath;

    public ApplyDisplaySettingsCommand(final QuPathGUI qupath) {
        this.qupath = qupath;

    }

    @Override
    public void run() {
        if (!Dialogs.showConfirmDialog("Apply Brightness And Contrast", "Apply current display settings to all images?\n\nWill apply on images with the same image type and number of channels."))
            return;

        apply();

    }

    public void apply() {
        ImageData currentImageData = qupath.getImageData();

        ImageDisplay currentImageDisplay = qupath.getViewer().getImageDisplay();
        ImageServer currentServer = currentImageData.getServer();

        // Get all images from Project
        List<ProjectImageEntry<BufferedImage>> imageList = qupath.getProject().getImageList();
        imageList.parallelStream().forEach(entry -> {
            ImageData<BufferedImage> imageData = null;

            try {
                imageData = entry.readImageData();

            ImageServer server = imageData.getServer();

            if (imageData == null) imageData = qupath.createNewImageData(server, true);
            if (currentImageData.getImageType().equals(imageData.getImageType()) && currentServer.getMetadata().getSizeC() == server.getMetadata().getSizeC()) {
                logger.info("Saving Display Settings for Image {}", entry.getImageName());

                imageData.setProperty(ImageDisplay.class.getName(), currentImageDisplay.toJSON(false) );
                entry.saveImageData( imageData );

            } else {
                logger.info( "Did not copy Display settings from {} to {}.\n" +
                                "Incompatible images: \n" +
                                        "\t\tType is {} vs {} \n" +
                                        "\t\t Channel number is {} vs {}",
                        ServerTools.getDisplayableImageName( currentServer ), ServerTools.getDisplayableImageName( server ),
                        currentImageData.getImageType(), imageData.getImageType(),
                        currentServer.getMetadata().getSizeC(), server.getMetadata().getSizeC()
                        );
            }

            } catch ( IOException e ) {
                logger.error( "Could not read image data for {}", entry.getImageName() );
                logger.error( e.toString() );
            }
        });
    }
}