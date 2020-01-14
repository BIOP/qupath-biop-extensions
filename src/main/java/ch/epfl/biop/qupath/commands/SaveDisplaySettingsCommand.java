package ch.epfl.biop.qupath.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.dialogs.DialogHelperFX;
import qupath.lib.images.servers.ServerTools;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class SaveDisplaySettingsCommand implements PathCommand {


    final static Logger logger = LoggerFactory.getLogger(ImageDisplay.class);
    private final QuPathGUI qupath;

    public SaveDisplaySettingsCommand(final QuPathGUI qupath) {

        this.qupath = qupath;

    }

    @Override
    public void run() {

        String imageName = ServerTools.getDisplayableImageName( qupath.getViewer().getServer() );
        File projectDirectory = qupath.getProject().getPath().toFile();
        DialogHelperFX dial = new DialogHelperFX(null);

        File toSave = dial.promptToSaveFile("Save Display Settings", projectDirectory, "Settings Based On " + imageName, null, "json");

        apply(toSave);

    }

    public void apply(File toSave) {

        ImageDisplay display = qupath.getViewer().getImageDisplay();

        String displaySettings = display.toJSON();

        try {

            BufferedWriter writer = new BufferedWriter(new FileWriter(toSave));
            logger.info("Writing Image Display Settings to " + toSave.getName());

            writer.write(displaySettings);
            writer.close();

        } catch (IOException e) {

            logger.error(e.getLocalizedMessage());

        }

    }

}