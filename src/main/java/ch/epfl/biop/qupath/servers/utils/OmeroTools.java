package ch.epfl.biop.qupath.servers.utils;

import omero.gateway.Gateway;
import omero.gateway.LoginCredentials;
import omero.gateway.SecurityContext;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.facility.RawDataFacility;
import omero.gateway.model.ExperimenterData;
import omero.gateway.model.ImageData;
import omero.gateway.model.PixelsData;
import omero.gateway.rnd.Plane2D;
import omero.log.SimpleLogger;

import java.util.ArrayList;
import java.util.Collection;

public class OmeroTools {


    public static Gateway omeroConnect(String hostname, int port, String userName, String password) throws Exception {
        //Omero Connect with credentials and simpleLogger
        LoginCredentials cred = new LoginCredentials();
        cred.getServer().setHost(hostname);
        cred.getServer().setPort(port);
        cred.getUser().setUsername(userName);
        cred.getUser().setPassword(password);
        SimpleLogger simpleLogger = new SimpleLogger();
        Gateway gateway = new Gateway(simpleLogger);
        gateway.connect(cred);
        return gateway;
    }

    public static Collection<ImageData> getImagesFromDataset(Gateway gateway, long DatasetID) throws Exception {
        //List all images contained in a Dataset
        BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
        SecurityContext ctx = getSecurityContext(gateway);
        Collection<Long> datasetIds = new ArrayList<>();
        datasetIds.add(new Long(DatasetID));
        return browse.getImagesForDatasets(ctx, datasetIds);

    }

    public static SecurityContext getSecurityContext(Gateway gateway) throws Exception {
        ExperimenterData exp = gateway.getLoggedInUser();
        long groupID = exp.getGroupId();
        SecurityContext ctx = new SecurityContext(groupID);
        return ctx;
    }

    public static Plane2D getRawPlane(Gateway gateway, long imageID) throws Exception {
        try (RawDataFacility rdf = gateway.getFacility(RawDataFacility.class)) {
            SecurityContext ctx = getSecurityContext(gateway);
            BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
            ImageData image = browse.getImage(ctx, imageID);

            PixelsData pixels = image.getDefaultPixels();
            int sizeZ = pixels.getSizeZ();
            int sizeT = pixels.getSizeT();
            int sizeC = pixels.getSizeC();

            Plane2D p = null;
            for (int z = 0; z < sizeZ; z++) {
                for (int t = 0; t < sizeT; t++) {
                    for (int c = 0; c < sizeC; c++) {
                        p = rdf.getPlane(ctx, pixels, z, t, c);
                    }
                }
            }

            return p;
        }

    }

    public static Plane2D getRawPlane(SecurityContext ctx, RawDataFacility rdf, PixelsData pixels, int z, int t, int c) throws Exception {
        Plane2D p = rdf.getPlane(ctx, pixels, z, t, c);
        return p;
    }

    public static Plane2D getRawPlanefromPixelsData(SecurityContext ctx, RawDataFacility rdf, PixelsData pixels, int z, int t, int c) throws Exception {
        Plane2D p = rdf.getPlane(ctx, pixels, z, t, c);
        return p;
    }

    public static Plane2D getRawTilefromPixelsData(SecurityContext ctx, RawDataFacility rdf, PixelsData pixels, int z, int t, int c, int x, int y, int w, int h) throws Exception {
        Plane2D p = rdf.getTile(ctx, pixels, z, t, c, x, y, w, h);
        return p;
    }

    public static PixelsData getPixelsDataFromOmeroID(long imageID, Gateway gateway, SecurityContext ctx) throws Exception {

        BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
        ImageData image = browse.getImage(ctx, imageID);
        PixelsData pixels = image.getDefaultPixels();
        return pixels;

    }

}