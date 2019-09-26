package ch.epfl.biop.qupath.plugins;

import ij.ImagePlus;
import ij.gui.*;
import ij.measure.Calibration;
import ij.plugin.filter.RankFilters;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.Blitter;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.objects.PathImagePlus;
import qupath.imagej.objects.ROIConverterIJ;
import qupath.imagej.processing.ROILabeling;
import qupath.lib.geom.ImmutableDimension;
import qupath.lib.images.ImageData;
import qupath.lib.images.PathImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.*;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.*;
import qupath.lib.roi.experimental.ShapeSimplifier;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


// Declaring an AbstractTileableDetectionPlugin will let QuPath do all the heavy lifting of making it work in parallel
public class SimpleThresholdDetection extends AbstractTileableDetectionPlugin<BufferedImage> {

    // The thresholdDetector is the class that will do the segmentation
    // the class is declared within this one as static
    transient private ThresholdDetector detector;

    // Call a logger so we can write to QuPath's log windows as needed
    private final static Logger logger = LoggerFactory.getLogger(SimpleThresholdDetection.class);

    // Not required but can be useful if we want to adjust parameters based on image information the plugin receives
    ParameterList params;

    /**
     * as it is currently built, the constructor only needs to handle creating the default parameters
     */
    public SimpleThresholdDetection() {

        // This list can be expanded. see @ParameterList
        params = new ParameterList();

        params.addTitleParameter("Setup parameters");

        params.addIntParameter("workingChannel", "Choose channel", 1, null, "Choose the channel number for running the threshold");
        params.addDoubleParameter("requestedPixelSizeMicrons", "Selected Pixel Size (microns)", 0.5, "um");
        params.addBooleanParameter("darkBackground", "Dark background", true, "If the signal of interest is bright, then set this to true");
        params.addDoubleParameter("gaussianSigma", "Gaussian blur value", 5.0, null, "How much to blur the image before setting a threshold");
        params.addDoubleParameter("threshold", "Threshold value", 200.0, null, "The absolute threshold value to use");
        params.addDoubleParameter("minparticle", "Threshold value", 200.0, null, "The absolute threshold value to use");
        params.addBooleanParameter("singleDetection", "As Single Detection", true, "Return que equivalent of a mask or individual non touching objects");
        params.setHiddenParameters(true, "singleDetection"); // Hide this for now until it works

    }


    @Override
    /**
     * This can help you compute the 'ideal' pixel size your plugin suggests (which can be overwritten by the user)
     * In this case we delegate that to our detector
     */
    protected double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
        return ThresholdDetector.getPreferredPixelSizeMicrons(imageData, params);
    }

    /**
     * This is what actually creates the object detector, which in this case is the inner ThresholdDetector class we
     * declared at the bottom
     * @param imageData
     * @param params
     * @return
     */
     @Override
    protected ObjectDetector<BufferedImage> createDetector( ImageData<BufferedImage> imageData, ParameterList params) {
        return new ThresholdDetector();
    }

    /**
     * This plugin does not care much about overlap if it gets called in parallel on multiple tiles, so we set this to 0
     * @param imageData
     * @param params
     * @return
     */
    @Override
    protected int getTileOverlap(ImageData<BufferedImage> imageData, ParameterList params) {
        return 50;
    }

    /**
     * This can help offer the user more sensible options based on the image data
     * @param imageData
     * @return
     */
    @Override
    public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {

        return params;
    }

    /**
     * the name of this plugin.
     * @return
     */
    @Override
    public String getName() {
        return "Simple Threshold Detection";
    }

    @Override
    public String getDescription() {
        return "Simple Parallel image threshold strategy";
    }

    /**
     * This string will be output at the end of processing in the progress bar when you will click Run
     * @return
     */
    @Override
    public String getLastResultsDescription() {
        return detector == null ? "" : detector.getLastResultsDescription();
    }

    /**
     * We overwrite postProcess in order to recombine the regions together, as we do not want them individually at this point
     * @param pluginRunner

    @Override
    protected void postprocess(PluginRunner<BufferedImage> pluginRunner) {
        // For each detection, check that the child objects within it are merged together
        PathObjectHierarchy hierarchy = pluginRunner.getHierarchy();

        List <PathObject> detections = hierarchy.getObjects(null, PathDetectionObject.class);

        // Keep only the ones that seem to be touching

        List<PathObject> merged = PathUtils.mergeTouchingPathObjects(detections);



        // Cleanup
        hierarchy.removeObjects(detections, false);
        hierarchy.addPathObjects(merged, false );
    }
     */

    /**
     *  This class is the one that will be called on the image (or images) to do all the dirty work
     *
     */
    static class ThresholdDetector implements ObjectDetector<BufferedImage> {
        PathImage<ImagePlus> pathImage;
        ImageProcessor ip;

        private List<PathObject> pathObjects = null;

        private ROI pathROI;

        private double pxSize;
        private boolean isDark;
        private double sigma;
        private double threshold;
        private int channel;
        private boolean isSingleDetection;


        @Override
        public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) {

            pxSize = params.getDoubleParameterValue("requestedPixelSizeMicrons");
            isDark = params.getBooleanParameterValue("darkBackground");
            sigma = params.getDoubleParameterValue("gaussianSigma");
            threshold = params.getDoubleParameterValue("threshold");
            channel = params.getIntParameterValue("workingChannel");
            channel = params.getIntParameterValue("workingChannel");
            isSingleDetection = params.getBooleanParameterValue("singleDetection");

            ImageServer<BufferedImage> server = imageData.getServer();
            pathImage = PathImagePlus.createPathImage(server, pathROI, ServerTools.getDownsampleFactor(server, getPreferredPixelSizeMicrons(imageData, params), true));

            ImagePlus imp = pathImage.getImage();
            imp.setOverlay(new Overlay());
            Calibration cal = imp.getCalibration();
            this.pathROI = pathROI;
            Roi roi = null;
            if (pathROI != null)
                roi = ROIConverterIJ.convertToIJRoi(pathROI, pathImage);

            pathObjects = new ArrayList<PathObject>();


            ip = pathImage.getImage().getStack().getProcessor(channel);
            ip.setRoi(roi);
            ip.resetMinAndMax();
            ip.resetThreshold();
            ip.blurGaussian(sigma);

            ROILabeling.clearOutside(ip, roi);

            Roi rectangle = new Roi(2, 2, ip.getWidth()-4, ip.getHeight()-4);
            ROILabeling.clearOutside(ip, rectangle);

            RankFilters rf = new RankFilters();
            ip.copyBits(ip, 0, 0, Blitter.AND);
            ip.setThreshold(getMinThr(), getMaxThr(), ImageProcessor.NO_LUT_UPDATE);
            ip.setAutoThreshold("", true, ImageProcessor.RED_LUT);

            if (!isSingleDetection) {
                List<PolygonRoi> rois2 = ROILabeling.getFilledPolygonROIs(ip, Wand.EIGHT_CONNECTED);
                rois2.stream().forEach(r -> imp.getOverlay().add(r));
                pathImage.getImage().show();
                rois2.forEach(r -> {
                    PolygonROI pROI = ROIConverterIJ.convertToPolygonROI(r, cal, pathImage.getDownsampleFactor());
                    pROI = ShapeSimplifier.simplifyPolygon(pROI, pathImage.getDownsampleFactor());
                    PathDetectionObject qPDo = new PathDetectionObject(pROI, null, null);

                    if (qPDo != null) {
                        pathObjects.add(qPDo);
                        qPDo.getMeasurementList().addMeasurement("Area", ImageStatistics.getStatistics(ip, ImageStatistics.AREA + ImageStatistics.LIMIT, cal).area);
                    }


                });
            } else {
                Roi r = new ThresholdToSelection().convert(ip);
                // Make sure it's always a shape ROI
                if (r instanceof PolygonRoi) {
                    r = new ShapeRoi(r);
                }

                AreaROI pROI = ROIConverterIJ.convertToAreaROI((ShapeRoi) r, cal, pathImage.getDownsampleFactor(), 0, pathROI.getZ(), pathROI.getT());
                pROI = (AreaROI) ShapeSimplifierAwt.simplifyShape(pROI, pathImage.getDownsampleFactor());
                PathDetectionObject qPDo = new PathDetectionObject(pROI, null, null);


                qPDo.getMeasurementList().addMeasurement("Area", ImageStatistics.getStatistics(ip, ImageStatistics.AREA + ImageStatistics.LIMIT, cal).area);

                if (qPDo != null) pathObjects.add(qPDo);
            }
            return pathObjects;
        }

        private double getMinThr() {
            int bitDepth = ip.getBitDepth();

            // dark is background, so the threshold min in the actual threshold value
            if (isDark) {
                return threshold;
            } else {
                // the darker pixels are the signal so the mininum should be 0 or -1e30 if 32 bit
                if (bitDepth == 32) {
                    return -1e30;
                } else {
                    return 0.0;
                }
            }
        }

        private double getMaxThr() {
            int bitDepth = ip.getBitDepth();

            // if it not dark, so the threshold min in the actual threshold value
            if (!isDark) {
                return threshold;
            } else {
                // the darker pixels are the signal so the mininum should be 0 or -1e30 if 32 bit
                if (bitDepth == 32) {
                    return 1e30;
                } else if (bitDepth == 16) {
                    return 65535;
                } else {
                    return 255;
                }
            }
        }


        public static double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
            if (imageData.getServer().hasPixelSizeMicrons())
                return Math.max(params.getDoubleParameterValue("requestedPixelSizeMicrons"), imageData.getServer().getAveragedPixelSizeMicrons());
            return Double.NaN;
        }
        @Override
        public String getLastResultsDescription() {
            if (pathObjects == null)
                return null;
            int nDetections = pathObjects.size();
            if (nDetections == 1)
                return "1 object detected";
            return String.format("%d objects detected", nDetections);
        }
    }

    @Override
    protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
        if (imageData == null)
            return;

        ParameterList params = getParameterList(imageData);

        // Determine appropriate sizes - get a downsample factor that is a power of 2
        double downsampleFactor = ServerTools.getDownsampleFactor(imageData.getServer(), getPreferredPixelSizeMicrons(imageData, params), true);
        int preferred = (int)(2048 * downsampleFactor);
//		int preferred = (int)(1536 * downsampleFactor);
//		int max = (int)(4096 * downsampleFactor);
        int max = (int)(3072 * downsampleFactor);
//		int max = (int)(2048 * downsampleFactor);
        ImmutableDimension sizePreferred = new ImmutableDimension(preferred, preferred);
        ImmutableDimension sizeMax = new ImmutableDimension(max, max);

        parentObject.clearPathObjects();

        // No tasks to complete
        Collection<? extends ROI> pathROIs = PathROIToolsAwt.computeTiledROIs(imageData, parentObject, sizePreferred, sizeMax, false, getTileOverlap(imageData, params));
        if (pathROIs.isEmpty())
            return;

        // Exactly one task to complete
        if (pathROIs.size() == 1 && pathROIs.iterator().next() == parentObject.getROI()) {
            tasks.add(new DetectionRunnable2(createDetector(imageData, params), getParameterList(imageData), imageData, parentObject, parentObject.getROI(), 0));
            return;
        }

        List<ParallelTileObject> tileList = new ArrayList<>();
        AtomicInteger countdown = new AtomicInteger(pathROIs.size());
        for (ROI pathROI : pathROIs) {
            ParallelTileObject tile = new ParallelTileObject(pathROI, imageData.getHierarchy(), countdown);
            parentObject.addPathObject(tile);
            for (ParallelTileObject tileTemp : tileList) {
                if (tileTemp.suggestNeighbor(tile))
                    tile.suggestNeighbor(tileTemp);
            }
            tileList.add(tile);
            tasks.add(new DetectionRunnable2(createDetector(imageData, params), params, imageData, tile, tile.getROI(), 0));
        }
        imageData.getHierarchy().fireHierarchyChangedEvent(this);
    }

    static class DetectionRunnable2<T> implements PathTask {

        final private static Logger logger = LoggerFactory.getLogger( DetectionPluginTools.class);

        ObjectDetector<T> detector;
        private ParameterList params;
        private PathObject parentObject;
        private ROI pathROI;
        private ImageData<T> imageData;
        private String result;
        private int overlapAmount;
        private Collection<PathObject> pathObjectsDetected;

        public DetectionRunnable2(final ObjectDetector<T> detector, final ParameterList params, final ImageData<T> imageData, final PathObject parentObject, final ROI pathROI, final int overlapAmount) {
            this.detector = detector;
            this.params = params;
            this.parentObject = parentObject;
            this.imageData = imageData;
            this.pathROI = pathROI;
            this.overlapAmount = overlapAmount;
        }


        /**
         * Check if the detection can run using the current ROI.
         * Current purpose is to return false if the ROI is a PointsROI... but may be overridden.
         * @return
         */
        protected boolean checkROI() {
            return !(pathROI instanceof PointsROI );
        }


        @Override
        public void run() {
            long startTime = System.currentTimeMillis();
            if (parentObject instanceof ParallelTileObject) {
                ((ParallelTileObject)parentObject).setIsProcessing(true);
                imageData.getHierarchy().fireObjectsChangedEvent(this, Collections.singleton(parentObject), true);
            }
            if (checkROI()) {
                pathObjectsDetected = detector.runDetection(imageData, params, pathROI);
                result = detector.getLastResultsDescription();
                long endTime = System.currentTimeMillis();
                if (result != null)
                    logger.info(result + String.format(" (processing time: %.2f seconds)", (endTime-startTime)/1000.));
                else
                    logger.info(parentObject + String.format(" (processing time: %.2f seconds)", (endTime-startTime)/1000.));
            } else {
                logger.info("Cannot run detection using ROI {}", pathROI);
            }
        }



        @Override
        public void taskComplete() {
            if (parentObject.getROI() == pathROI) {
                if (!Thread.currentThread().isInterrupted()) {
                    parentObject.clearPathObjects();
                }
                if (pathObjectsDetected != null) {
                    parentObject.addPathObjects(pathObjectsDetected);
                }
                if (parentObject instanceof ParallelTileObject)
                    ((ParallelTileObject)parentObject).setComplete();
            } else if (!parentObject.hasChildren() || overlapAmount <= 0) {
                parentObject.addPathObjects(pathObjectsDetected);
            } else if (pathObjectsDetected != null && !pathObjectsDetected.isEmpty()) {
                parentObject.addPathObjects(pathObjectsDetected);
            }

            logger.info("Completed Task... {} objects detected", pathObjectsDetected.size());

            imageData.getHierarchy().fireHierarchyChangedEvent(parentObject);

            pathObjectsDetected = null;
            parentObject = null;
            imageData = null;
        }

        @Override
        public String getLastResultsDescription() {
            return result;
        }

    }
}