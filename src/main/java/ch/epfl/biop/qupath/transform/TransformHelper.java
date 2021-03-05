package ch.epfl.biop.qupath.transform;

import com.opencsv.CSVReader;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.inverse.WrappedIterativeInvertibleRealTransform;
import org.locationtech.jts.geom.*;
import qupath.lib.gui.QuPathApp;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class which briges real transformation of the imglib2 world and
 * makes it easily usable into JTS world, mainly used by QuPath
 *
 * See initial forum thread : https://forum.image.sc/t/qupath-arbitrarily-transform-detections-and-annotations/49674
 * @author Nicolas Chiaruttini, EPFL, 2020
 */

public class TransformHelper {

    public static void main(String... args) throws Exception {
        QuPathApp.launch(QuPathApp.class);
    }

    static void testRectangleTransform() throws Exception {

        // Retrieves a BigWarp landmark file
        String directory = "src\\test\\resources\\";
        String fileName = "landmarks-v1.csv";
        File bwLandmarkFile = new File(directory+fileName);
        RealTransform rt = realTransformFromBigWarpFile(bwLandmarkFile, false);

        // Let's make a simple rectangle
        Geometry rectangle = GeometryTools.createRectangle(0,0,100,100);
        System.out.println("Initial rectangle : "+rectangle.toString());

        Geometry transformedRectangle = rectangle.copy();

        // Apply the transformation
        transformedRectangle.apply(TransformHelper.getJTSFilter(rt));

        System.out.println("Transformed rectangle : "+transformedRectangle.toString());
    }

    /**
     * Returns a transformed PathObject (Annotation or detection) based
     * on the original geometry of the input path object
     * @param object qupath annotation or detection object
     * @param transform jts free form transformation
     */
    public static PathObject transformPathObject(PathObject object, CoordinateSequenceFilter transform, boolean checkGeometryValidity) throws Exception {

        ROI original_roi = object.getROI();

        Geometry geometry = original_roi.getGeometry();

        GeometryTools.attemptOperation(geometry, (g) -> {
            g.apply(transform);
            return g;
        });

        if (checkGeometryValidity) {
            if (!geometry.isValid()) {
                throw new Exception("Invalid geometry for transformed object"+object);
            }
        }

        ROI transformed_roi = GeometryTools.geometryToROI(geometry, original_roi.getImagePlane());

        if (object instanceof PathAnnotationObject) {
            return PathObjects.createAnnotationObject(transformed_roi);
        } else if (object instanceof PathDetectionObject) {
            return PathObjects.createDetectionObject(transformed_roi);
        } else {
            throw new Exception("Unknown PathObject class for class "+object.getClass().getSimpleName());
        }
    }

    /**
     * Creates a RealTransform object from a BigWarp landmark file
     *
     * @param f bigwarp landmark file
     * @param force3d forces to return a 3d transform, even if the landmarks are 2 dimensional,
     *                in which case the 3rd dimension is unmodified
     * @return an imglib2 {@link RealTransform} object
     * @throws Exception if the file does not exists or is not valid
     */
    public static RealTransform realTransformFromBigWarpFile(File f, boolean force3d) throws Exception{

        CSVReader reader = new CSVReader( new FileReader( f.getAbsolutePath() ));
        List< String[] > rows;
        rows = reader.readAll();
        reader.close();
        if( rows == null || rows.size() < 1 )
        {
            throw new IOException("Wrong number of rows in file "+f.getAbsolutePath());
        }

        int ndims = 3;
        int expectedRowLength = 8;
        int numRowsTmp = 0;

        ArrayList<double[]> movingPts = new ArrayList<>();
        ArrayList<double[]>	targetPts = new ArrayList<>();

        for( String[] row : rows )
        {
            // detect a file with 2d landmarks
            if( numRowsTmp == 0 && // only check for the first row
                    row.length == 6 )
            {
                ndims = 2;
                expectedRowLength = 6;
            }

            if( row.length != expectedRowLength  )
                throw new IOException( "Invalid file - not enough columns" );

            double[] movingPt = new double[ ndims ];
            double[] targetPt = new double[ ndims ];

            int k = 2;
            for( int d = 0; d < ndims; d++ )
                movingPt[ d ] = Double.parseDouble( row[ k++ ]);

            for( int d = 0; d < ndims; d++ )
                targetPt[ d ] = Double.parseDouble( row[ k++ ]);

            {
                movingPts.add( movingPt );
                targetPts.add( targetPt );
            }
            numRowsTmp++;
        }

        List<RealPoint> moving_pts = new ArrayList<>();
        List<RealPoint> fixed_pts = new ArrayList<>();

        for (int indexLandmark = 0; indexLandmark<numRowsTmp; indexLandmark++) {

            RealPoint moving = new RealPoint(ndims);
            RealPoint fixed = new RealPoint(ndims);

            moving.setPosition(movingPts.get(indexLandmark));
            fixed.setPosition(targetPts.get(indexLandmark));

            moving_pts.add(moving);
            fixed_pts.add(fixed);
        }

        ThinplateSplineTransform tst = getTransform(moving_pts, fixed_pts, false);

        InvertibleRealTransform irt = new WrappedIterativeInvertibleRealTransform<>(tst);

        if (force3d&&(irt.numSourceDimensions()==2)) {
            return new Wrapped2DTransformAs3D(irt);
        } else {
            return irt;
        }
    }

    /**
     * Gets an imglib2 realtransform object for a number of landmarks
     *
     * @param moving_pts moving points
     * @param fixed_pts fixed points
     * @param force2d returns a 2d realtransform only and ignores 3rd dimension
     * @return
     */
    public static ThinplateSplineTransform getTransform(List<RealPoint> moving_pts, List<RealPoint> fixed_pts, boolean force2d) {
        int nbDimensions = moving_pts.get(0).numDimensions();
        int nbLandmarks = moving_pts.size();

        if (force2d) nbDimensions = 2;

        double[][] mPts = new double[nbDimensions][nbLandmarks];
        double[][] fPts = new double[nbDimensions][nbLandmarks];

        for (int i = 0;i<nbLandmarks;i++) {
            for (int d = 0; d<nbDimensions; d++) {
                fPts[d][i] = fixed_pts.get(i).getDoublePosition(d);
                //System.out.println("fPts["+d+"]["+i+"]=" +fPts[d][i]);
            }
            for (int d = 0; d<nbDimensions; d++) {
                mPts[d][i] = moving_pts.get(i).getDoublePosition(d);
                //System.out.println("mPts["+d+"]["+i+"]=" +mPts[d][i]);
            }
        }

        return new ThinplateSplineTransform(fPts, mPts);
    }

    /**
     * Gets an imglib2 realtransform object and returned the equivalent
     * JTS {@link CoordinateSequenceFilter} operation which can be applied to
     * {@link Geometry}.
     *
     * The 3rd dimension is ignored.
     *
     * @param rt imglib2 realtransform object
     * @return the equivalent JTS {@link CoordinateSequenceFilter} operation which can be applied to {@link Geometry}.
     */
    public static CoordinateSequenceFilter getJTSFilter(RealTransform rt) {
        return new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                RealPoint pt = new RealPoint(2);
                pt.setPosition(seq.getOrdinate(i, 0),0);
                pt.setPosition(seq.getOrdinate(i, 1),1);
                rt.apply(pt,pt);
                seq.setOrdinate(i, 0, pt.getDoublePosition(0));
                seq.setOrdinate(i, 1, pt.getDoublePosition(1));
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        };
    }

}
