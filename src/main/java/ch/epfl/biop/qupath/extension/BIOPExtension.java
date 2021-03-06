package ch.epfl.biop.qupath.extension;

import ch.epfl.biop.qupath.atlas.allen.commands.LoadAtlasRoisToQuPathCommand;
import ch.epfl.biop.qupath.analysis.RNAScopeCounts2;
import ch.epfl.biop.qupath.commands.ApplyDisplaySettingsCommand;
import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.ActionTools.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import static qupath.lib.gui.ActionTools.getAnnotatedActions;

public class BIOPExtension implements QuPathExtension {

    private final static Logger logger = LoggerFactory.getLogger( BIOPExtension.class);

    /**
     * Commands based on OpenCV.
     */
    @SuppressWarnings("javadoc")
    public static class RNAScopeCommands {

        @ActionMenu("BIOP>RNAScope>")
        @ActionDescription("RNAScope analysis commands")
        public final Action actionRNAScope;

        private RNAScopeCommands(QuPathGUI qupath) {
            actionRNAScope = qupath.createPluginAction("Perform RNA Scope Analysis (Experimental)", RNAScopeCounts2.class, null);
        }
    }

    public static class ABBACommands {
        @ActionMenu("BIOP>Atlas>Load Atlas Annotations into Open Image")
        @ActionDescription("Commands for Allen Brain Atlas Communication")
        public final Action actionABBA;

        private ABBACommands(QuPathGUI qupath) {
            actionABBA = qupath.createImageDataAction( project -> new LoadAtlasRoisToQuPathCommand( qupath ).run());
        }
    }

    @SuppressWarnings("javadoc")
    public static class DisplayCommands {

        @ActionMenu("BIOP>Display>Apply Display Settings")
        @ActionDescription("Display commands")
        public final Action actionApplyDisplay;

        private DisplayCommands(QuPathGUI qupath) {
            actionApplyDisplay = qupath.createProjectAction( project -> new ApplyDisplaySettingsCommand( qupath ).run() );
            //actionApplyDisplay = qupath.createImageDataAction(imageData -> new ApplyDisplaySettingsCommand( qupath ).run());
        }
    }

    @Override
    public void installExtension( QuPathGUI qupath ) {
        logger.debug("Installing extension");
        qupath.installActions(getAnnotatedActions(new RNAScopeCommands(qupath)));
        qupath.installActions(getAnnotatedActions(new ABBACommands(qupath)));
        qupath.installActions(getAnnotatedActions(new DisplayCommands(qupath)));
    }

    @Override
    public String getName( ) {
        return "BIOP Commands";
    }

    @Override
    public String getDescription( ) {
        return "Stuff Made at the BIOP, with love.";
    }
}
