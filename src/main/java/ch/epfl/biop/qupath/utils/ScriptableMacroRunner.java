package ch.epfl.biop.qupath.utils;

import ch.epfl.biop.qupath.utils.internal.ImageJMacroRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static qupath.lib.scripting.QP.*;

/**
 * This is a wrapper around the MacroRunner to make it easier to script
 *
 * @author Olivier Burri
 *
 */
public class ScriptableMacroRunner {

    final private static Logger logger = LoggerFactory.getLogger(GUIUtils.class);

    ParameterList parameters;
    PathObject pathObject;
    QuPathViewer viewer;

    ImageData<?> imageData;
    ImageDisplay imageDisplay;
    String macroText;

    /**
     * A Simple public class that must do a few things
     * 1. Keep references to any element the ImageJMacroRunner needs
     * Like the parameters list, the PathObject we want o send to ImageJ and so on
     * @see ImageJMacroRunner
     *
     * We are hacking the and modifying ParameterList to store the parameters we want. Thing is there is no
     * parameters.setValue(key, value) so we need to use 'add###Parameter' each time.
     * @see ParameterList
     *
     * */
    public ScriptableMacroRunner() {

        // Initialize the MacroRunner with some sensible defaults
        this.viewer = QuPathGUI.getInstance().getViewer();
        this.imageData = getCurrentImageData();

        // This gives us a default parameters list
        this.parameters = new ImageJMacroRunner(QuPathGUI.getInstance()).getParameterList(null);

        // Create an annotation of the whole image, in case the user doens't give us one
        this.pathObject = PathObjects.createAnnotationObject( ROIs.createRectangleROI( 0, 0, imageData.getServer().getWidth(), imageData.getServer().getHeight(), null));

        // We use this parameter to make sure that we remove the rectangle ROI at the end of the run, because we don't need it
        this.parameters.addBooleanParameter("processWholeImage", null, true);

        // Set a downsample factor of 16, in case the user goes crazy and tries to run this with only the defaults
        setDownsample(16);
    }

    // This sets the object that gets sent (with or without its child objects, that's another option
    public void setPathObject(PathObject pathObject) {
        this.pathObject = pathObject;
        this.parameters.addBooleanParameter("processWholeImage", null, false);
    }

    // Setting a downsample factor for the script
    public void setDownsample(int downsampleFactor) {
        this.parameters.addDoubleParameter("downsampleFactor", "Downsample factor", downsampleFactor);
    }

    // Are we sending the currently set PathObject (with setPathObject) to ImageJ as a ROI?
    public void setSendROI(boolean sendROI) {
        this.parameters.addBooleanParameter("sendROI", "Send ROI to ImageJ", sendROI);
    }

    // Should QuPath send all the child objects as an Overlay for ImageJ
    public void setSendOverlay(boolean setOverlay) {
        this.parameters.addBooleanParameter("sendOverlay", "Send overlay to ImageJ", setOverlay);
    }

    // Should QuPath get rid of the child objects of the PathObject?
    public void setClearObjects(boolean clearObjects) {
        this.parameters.addBooleanParameter("clearObjects", "Clear current child objects", clearObjects);
    }

    // Should QuPath pick up the last ROI on the image and get it as an annotation?
    public void setGetROI(boolean getRoi) {
        this.parameters.addBooleanParameter("getROI", "Create annotation from ImageJ ROI", getRoi);
    }

    // should QuPath get the IamgEJ Overlay and convert it to Annotations or Detections?
    public void setGetOverlay(boolean getOverlay) {
        this.parameters.addBooleanParameter("getOverlay", "Get objects from ImageJ overlay", getOverlay);
    }

    // Text choice for whether we want detections or annotations
    public void setGetOverlayAs(String overlayAs) {
        if (overlayAs.equals("Detections"))
            this.parameters.addChoiceParameter("getOverlayAs", "Get objects as", "Detections", Arrays.asList("Detections", "Annotations"));
        if (overlayAs.equals("Annotations"))
            this.parameters.addChoiceParameter("getOverlayAs", "Get objects as", "Annotations", Arrays.asList("Detections", "Annotations"));
    }

    // This sets the actual macro code to run
    void setMacroText(String text) {
        this.macroText = text;
    }

    // Maybe users want to set their parameters themselves... we let them here
    public void setParameters(ParameterList parameters) {
        this.parameters = parameters;
    }

    /**
     * This is where the work happens
     * We add the whole image annotation if needed and run the macroRunner
     * We then remove the whole image annotation if we need it
     */
    public void run() {
        // Get the previous PathObjects
        List<PathObject> pathObjectsBefore = Arrays.asList(getAllObjects());

        if (this.parameters.getBooleanParameterValue("processWholeImage"))
            viewer.getHierarchy().addPathObject( this.pathObject );

        ImageJMacroRunner.runMacro( this.parameters, (ImageData<BufferedImage>) this.imageData, this.imageDisplay, this.pathObject, this.macroText );

        //Remove full image rectangle if we processed the whole image, but keep all child objects
        if (this.parameters.getBooleanParameterValue("processWholeImage"))
            viewer.getHierarchy().removeObject(this.pathObject, true);


        List<PathObject> pathObjectsAfter = Arrays.asList(getAllObjects());

        // Get all the objects that are new. This should give us the disjoint objects.
        // That is the objects that are unique to either list
        List<PathObject> pathObjectsList = Stream.concat(
                pathObjectsAfter.stream().filter(c->!pathObjectsBefore.contains(c)),
                pathObjectsBefore.stream().filter(c->!pathObjectsAfter.contains(c))
        ).collect(Collectors.toList());

        // Get the name, if has a parenthesis, then give it a class
        // Could go somewhere deeper in QuPath but this way the behavior is limited to the scriptablemacrorunner
        // TODO, is that useful? Maybe it would be good to do it. add in IJTools.convertToPathObject
        for ( PathObject po : pathObjectsList ){

            String pathObjectName = po.getName();

            if ( pathObjectName != null ) {
                Pattern roiNamePattern = Pattern.compile( "(?<thename>.*)\\s?\\((?<theclass>.*)\\)" );
                Matcher nameMatcher = roiNamePattern.matcher(pathObjectName);

                if (  nameMatcher.find() ) {
                    String pathObjectNameNew = nameMatcher.group( "thename" ).trim();
                    String pathObjectClassName = nameMatcher.group( "theclass" ).trim();

                    po.setName( pathObjectNameNew );
                    po.setPathClass( getPathClass(pathObjectClassName) );
                }
            }
        }

        fireHierarchyUpdate();
    }
}
