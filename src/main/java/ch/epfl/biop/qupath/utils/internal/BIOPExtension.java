package ch.epfl.biop.qupath.utils.internal;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

public class BIOPExtension implements QuPathExtension {
    @Override
    public void installExtension( QuPathGUI qupath ) {
        // Install command to export Annotations from QuPath to a folder

    }

    @Override
    public String getName( ) {
        return "BIOP Scripting Extensions";
    }

    @Override
    public String getDescription( ) {
        return "BIOP Scripting Extensions to ease creating scripts in a multiuser facility";
    }
}
