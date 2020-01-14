package ch.epfl.biop.qupath.utils;

import ch.epfl.biop.qupath.commands.ApplyDisplaySettingsCommand;
import ij.ImagePlus;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.gui.IJExtension;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.tools.ColorToolsFX;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.PathImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.RegionRequest;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Here we store a bunch of utility functions that are relevant to QuPath's User interface
 * Like setting colors, lookup tables and such
 */
public class GUIUtils extends QPEx {

    final private static Logger logger = LoggerFactory.getLogger(GUIUtils.class);

    final public static String PROJECT_BASE_DIR = "{%PROJECT}";


    /**
     * Returns an ImagePlus object with or without a ROI and with or without an overlay (if there are child objects)
     * Careful as this will send the CURRENT viewer in batch mode. It will work but be careful if you have different
     * image with different channels or something like that
     *
     * @param pathObject                the annotation or detection you want to use as bounds for the image
     * @param downsample                the downsample factor
     * @param sendPathObjectAsRoi       should the pathObject ROI be added to the ImagePlus
     * @param sendChildObjectsInOverlay should the child objects be put in the Overlay of the ImagePlus
     * @return image the ImagePlus with the ROI or Overlay as selected
     */
    public static ImagePlus getImagePlus(PathObject pathObject, int downsample, boolean sendPathObjectAsRoi, boolean sendChildObjectsInOverlay) {

        ImagePlus imp = getImagePlus( pathObject, downsample, sendPathObjectAsRoi, sendChildObjectsInOverlay, null);
        return imp;
    }

    public static ImagePlus getImagePlus(PathObject pathObject, int downsample, boolean sendPathObjectAsRoi, boolean sendChildObjectsInOverlay, ArrayList<String> channelNames) {
        PathImage<ImagePlus> pathImage = getPathImage( pathObject, downsample, sendPathObjectAsRoi, sendChildObjectsInOverlay, channelNames );
        ImagePlus imp = pathImage.getImage();
        // QuPath does not send rectangular ROIs to ImageJ, which kind of makes sense but could break some behavior, especially if we requested the PathObject as a ROI
        if(sendPathObjectAsRoi && imp.getRoi() == null) {
            imp.setRoi(0,0, imp.getWidth(), imp.getHeight());
        }
        return imp;
    }

    public static PathImage<ImagePlus> getPathImage(PathObject pathObject, int downsample, boolean sendPathObjectAsRoi, boolean sendChildObjectsInOverlay, ArrayList<String> channelNames) {
        ImageData imageData = getCurrentImageData();
        ImageServer server = imageData.getServer( );
        QuPathViewer viewer = getCurrentViewer( );
        PathObjectHierarchy hierarchy = getCurrentHierarchy( );
        ImageDisplay display = null;
        if ( channelNames != null ) {
            display = new ImageDisplay( imageData );
            List<ChannelDisplayInfo> channels = display.availableChannels( );

            for ( int i = 0; i < channels.size( ); i++ )
                display.setChannelSelected( channels.get( i ), channelNames.contains( channels.get( i ).getName( ) ) );

        }

        RegionRequest request = RegionRequest.createInstance(imageData.getServerPath(), downsample, pathObject.getROI());


        PathImage<ImagePlus> pathImage = null;
        try {
            pathImage = IJExtension.extractROIWithOverlay( server, pathObject, hierarchy, request, sendPathObjectAsRoi, viewer.getOverlayOptions() );
        } catch ( IOException e ) {
            logger.error( "Could not Extract ROI:\n {}", e.getMessage());
        }

        if (!sendChildObjectsInOverlay) pathImage.getImage().setOverlay(null);
        return pathImage;
    }
    /**
     * Returns a list with the minimum and maximum display values for the given channel
     *
     * @param channel the channel number, 1 based
     * @return minmax a list with the channel's min and max value
     * Example:
     * <pre>
     *  {@code
     *  import ch.epfl.biop.qupath.utils.*;
     *
     *  List<Integer> minmax = GUIUtils.getChannelMinMax(1);
     *  }
     * </pre>
     */
    public static List<Double> getChannelMinMax(int channel) {
        ChannelDisplayInfo selectedChannel = getSelectedChannelInfo(channel);

        Double min = Double.valueOf( selectedChannel.getMinDisplay() );
        Double max = Double.valueOf( selectedChannel.getMaxDisplay() );

        ArrayList<Double> minmax = new ArrayList<>();
        minmax.add(min);
        minmax.add(max);

        return minmax;
    }

    /**
     * Returns a list with teh minimum and maximum display values for the given channel
     *
     * @param channel the channel name, as a String
     * @return minmax a list with the channel's min and max value
     */
    public static List<Integer> getChannelMinMax(String channel) {
        ChannelDisplayInfo selectedChannel = getSelectedChannelInfo(channel);
        Integer min = Math.round(selectedChannel.getMinDisplay());
        Integer max = Math.round(selectedChannel.getMaxDisplay());

        ArrayList<Integer> minmax = new ArrayList<>();
        minmax.add(min);
        minmax.add(max);

        return minmax;

    }

    /**
     * Sets the channels in the list to active and deactivated the ones not in it
     *
     * @param theChannels a list of channels (1 based) that should be active
     */
    public static void setActiveChannels(ArrayList<Integer> theChannels) {
        QuPathViewer viewer = getCurrentViewer();
        ImageDisplay display = getCurrentImageDisplay();
        List<ChannelDisplayInfo> channels = display.availableChannels();

        for (int i = 0; i < channels.size(); i++)
            display.setChannelSelected(channels.get(i), theChannels.contains(i + 1));

        display.saveChannelColorProperties();
        if (isNotBatch()) viewer.repaintEntireImage();
    }

    /**
     * Sets the channels in the list to active and deactivated the ones not in it
     *
     * @param channelNames a list of channel names, as Strings that should be active
     */
    public static void setActiveChannelsByName(ArrayList<String> channelNames) {
        QuPathViewer viewer = getCurrentViewer();
        ImageDisplay display = getCurrentImageDisplay();

        List<ChannelDisplayInfo> channels = display.availableChannels();

        for (int i = 0; i < channels.size(); i++)
            display.setChannelSelected(channels.get(i), channelNames.contains(channels.get(i).getName()));

        display.saveChannelColorProperties();
        if (isNotBatch()) viewer.repaintEntireImage();
    }

    /**
     * Sets the minimum and maximum display range for a channel, defined by its name
     *
     * @param channelName the name of the channel, as seen in the Brightness Contrast Tool
     * @param min         minimum channel display value
     * @param max         maximum channel display value
     */
    public static void setChannelMinMax(String channelName, double min, double max) {
        QuPathViewer viewer = getCurrentViewer();
        ImageDisplay display = getCurrentImageDisplay();

        ChannelDisplayInfo selectedChannel = getSelectedChannelInfo(channelName);
        logger.info("Setting Min and Max for channel " + selectedChannel);
        display.setMinMaxDisplay(selectedChannel, (float) min, (float) max);
        if (isNotBatch()) viewer.repaintEntireImage();
    }

    public static void setChannelsMinMax(List<Integer> channelIndexes , List<Integer> minValues, List<Integer> maxValues){

        for (int i = 0 ; i< channelIndexes.size() ; i++){
            //  TODO check for errors ! DELETE this
            setChannelMinMax( channelIndexes.get(i), minValues.get(i) , maxValues.get(i) );
        }

    }

    /**
     * Sets the minimum and maximum display range for a channel, defined by its index (1 based)
     *
     * @param channelID channel index, 1 based
     * @param min       minimum channel display value
     * @param max       maximum channel display value
     */
    public static void setChannelMinMax(int channelID, int min, int max) {
        QuPathViewer viewer = getCurrentViewer();
        ImageDisplay display = viewer.getImageDisplay();
        List<ChannelDisplayInfo> channels = display.availableChannels();
        setChannelMinMax(channels.get(channelID - 1).getName(), min, max);
    }

    /**
     * Sets the color of the selected channel
     *
     * @param channelName the name of the channel
     * @param color       the color, defined through JavaFX's Color Class
     * @see javafx.scene.paint.Color
     */
    public static void setChannelColor(String channelName, Color color) {
        QuPathViewer viewer = getCurrentViewer();
        ImageDisplay display = getCurrentImageDisplay();

        ChannelDisplayInfo selectedChannel = getSelectedChannelInfo(channelName);
        if (selectedChannel instanceof ChannelDisplayInfo.AbstractSingleChannelInfo) {
            ChannelDisplayInfo.DirectServerChannelInfo multiInfo = (ChannelDisplayInfo.DirectServerChannelInfo) selectedChannel;

            Integer channelRGB = ColorToolsFX.getRGB(color);
            multiInfo.setLUTColor(channelRGB);
            logger.info("Set Color of channel " + selectedChannel + " to " + color);
            display.saveChannelColorProperties();
            if (isNotBatch()) viewer.repaintEntireImage();

        } else {
            logger.info("Cannot Set LUT for Channel " + selectedChannel);
        }
    }

    /**
     * Sets the color of the selected channel
     *
     * @param channel the index of the channel, 1 based
     * @param color   the color, defined through JavaFX's Color Class
     * @see javafx.scene.paint.Color
     */
    public static void setChannelColor(int channel, Color color) {
        ChannelDisplayInfo selectedChannel = getSelectedChannelInfo(channel);
        setChannelColor(selectedChannel.getName(), color);
    }

    public static Integer getChannelColor(int channel ) {
        ChannelDisplayInfo selectedChannel = getSelectedChannelInfo(channel);
        Integer rgb = selectedChannel.getColor( );
        return rgb;
    }

    public static Integer getChannelColor(String channel ) {
        ChannelDisplayInfo selectedChannel = getSelectedChannelInfo(channel);
        Integer rgb = selectedChannel.getColor( );
        return rgb;
    }
    /**
     * Saves the current display settings to a desired file
     *
     * @param fileToSave the file to be created which will contain a serialized JSON ImageDisplay
     */
    public static void saveDisplaySettings( File fileToSave) {
        QuPathViewer viewer = getCurrentViewer();
        ImageDisplay display = viewer.getImageDisplay();

        String displaySettings = display.toJSON();

        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(fileToSave));
            logger.info("Writing Image Display Settings to " + fileToSave.getName());

            writer.write(displaySettings);
            writer.close();
        } catch ( IOException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    /**
     * Loads the display settings (Brightness, contrast, LUT, Min-Max) from a json serialized file
     *
     * @param fileToLoad the file to read the first line from
     */
    public static void loadDisplaySettings(File fileToLoad) {

        QuPathViewer viewer = getCurrentViewer();
        ImageDisplay display = getCurrentImageDisplay();

        try {
            BufferedReader reader = new BufferedReader(new FileReader(fileToLoad));
            String displaySettings = reader.readLine();
            reader.close();
            display.updateFromJSON(displaySettings);
            display.saveChannelColorProperties();
            display.updateChannelOptions(false);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (isNotBatch()) viewer.repaintEntireImage();
    }

    public static void applyDisplaySettingsToProject() {
        ApplyDisplaySettingsCommand abcc = new ApplyDisplaySettingsCommand(getQuPath());
        abcc.apply();
    }

    public static void log(String text) {
        LoggerFactory.getLogger(GUIUtils.class).info(text);
    }
    /**
     * Internal function to get the channel the user wants, based on its index
     *
     * @param channel the index of the channel, 1 based
     * @return selectedChannel the channel that was selected, which is of type ChannelDisplayInfo
     * @see ChannelDisplayInfo
     */
    private static ChannelDisplayInfo getSelectedChannelInfo(int channel) {
        ImageDisplay display = getCurrentImageDisplay();

        List<ChannelDisplayInfo> channels = display.availableChannels();
        ChannelDisplayInfo selectedChannel = channels.get(channel - 1);
        return selectedChannel;
    }

    /**
     * Internal function to get the channel the user wants, based on its index
     *
     * @param channelName the name of the channel
     * @return selectedChannel the channel that was selected, which is of type ChannelDisplayInfo
     * @see ChannelDisplayInfo
     */
    private static ChannelDisplayInfo getSelectedChannelInfo(String channelName) {
        ImageDisplay display = getCurrentImageDisplay();

        List<ChannelDisplayInfo> channels = display.availableChannels();
        ChannelDisplayInfo selectedChannel = channels.stream().filter(ch -> ch.getName().equals(channelName)).findFirst().get();
        return selectedChannel;
    }

    /**
     * Gets the current image display or creates one in the case we need it when we work in batch mode
     *
     * @return display the ImageDisplay that matches what we need.
     * @see ImageDisplay
     */
    private static ImageDisplay getCurrentImageDisplay() {
        QuPathViewer viewer = getCurrentViewer();
        ImageData imageData = getCurrentImageData();
        ImageDisplay display;

        // If we're in batch mode
        if (isNotBatch()) {
            display = viewer.getImageDisplay();
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
    private static boolean isNotBatch() {
        QuPathViewer viewer = getCurrentViewer();
        ImageData imageData = getCurrentImageData();
        return viewer.getImageData().equals(imageData);

    }
}