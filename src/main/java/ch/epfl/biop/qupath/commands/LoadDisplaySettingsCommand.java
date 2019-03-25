package ch.epfl.biop.qupath.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.helpers.dialogs.DialogHelperFX;
import qupath.lib.gui.viewer.QuPathViewer;

import java.io.*;

public class LoadDisplaySettingsCommand implements PathCommand {


    final static Logger logger = LoggerFactory.getLogger(ImageDisplay.class);
    private final QuPathGUI qupath;

    public LoadDisplaySettingsCommand(final QuPathGUI qupath) {

        this.qupath = qupath;

    }

    @Override
    public void run() {

        File projectDirectory = qupath.getProject().getPath().toFile();

        DialogHelperFX dial = new DialogHelperFX();
        File toLoad = dial.promptForFile("Load Display Settings", projectDirectory, null, "json");

        apply(toLoad);

    }

    public void apply(File toLoad) {

        QuPathViewer viewer = qupath.getViewer();
        ImageDisplay display = qupath.getViewer().getImageDisplay();

        try {

            BufferedReader reader = new BufferedReader(new FileReader(toLoad));
            String displaySettings = reader.readLine();
            reader.close();
            display.updateFromJSON(displaySettings);
            display.saveChannelColorProperties();
            display.updateChannelOptions(false);

        } catch (IOException e) {

            e.printStackTrace();

        }

        viewer.repaintEntireImage();

    }

}
