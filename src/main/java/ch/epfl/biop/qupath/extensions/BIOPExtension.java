package ch.epfl.biop.qupath.extensions;

import ch.epfl.biop.qupath.commands.ApplyDisplaySettingsCommand;
import ch.epfl.biop.qupath.commands.LoadDisplaySettingsCommand;
import ch.epfl.biop.qupath.commands.SaveDisplaySettingsCommand;
import javafx.scene.control.Menu;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

public class BIOPExtension implements QuPathExtension {
    @Override
    public void installExtension(QuPathGUI qupath) {
        ApplyDisplaySettingsCommand applyDisplaySettingsCommand = new ApplyDisplaySettingsCommand(qupath);
        SaveDisplaySettingsCommand saveDisplaySettingsCommand = new SaveDisplaySettingsCommand(qupath);
        LoadDisplaySettingsCommand loadDisplaySettingsCommand = new LoadDisplaySettingsCommand(qupath);

        Menu biop = qupath.getMenu("BIOP>Display Settings...", true);
        QuPathGUI.addMenuItems(biop,
                QuPathGUI.createCommandAction(saveDisplaySettingsCommand, "Save current display settings to file..."),
                QuPathGUI.createCommandAction(loadDisplaySettingsCommand, "Load display settings from file..."),
                null,
                QuPathGUI.createCommandAction(applyDisplaySettingsCommand, "Apply display settings to similar images in project...")
        );
    }

    @Override
    public String getName() {
        return "BIOP Extensions for QuPath";
    }

    @Override
    public String getDescription() {
        return "Tools, utilities and shortcuts for QuPath, by the BIOP";
    }
}
