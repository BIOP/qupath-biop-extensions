import qupath.lib.objects.PathObjects
import ch.epfl.biop.qupath.transform.TransformHelper
import java.io.File
import org.locationtech.jts.geom.*
import java.util.ArrayList

// Gets all annotations from the file
def all_annotations = getAnnotationObjects().findAll { true }//it.getPathClass() == getPathClass("Training") }

def all_detections = new ArrayList(getDetectionObjects().findAll{ true })

// Opens BigWarp landmark file
def directory = "src\\test\\resources\\"
//def fileName = "landmarks-v1.csv"
//def bwLandmarkFile = new File(directory+fileName)
//def rt = TransformHelper.realTransformFromBigWarpFile(bwLandmarkFile, false)

def fileName = "transform_6_3.json"
def rt = TransformHelper.getRealTransform(new File(directory+fileName))

// Makes JTS transformer

def transformer = TransformHelper.getJTSFilter(rt)

// Transforms all annotations
def transformed_annotations = all_annotations.collect{ annotation ->
    def transformed_annotation = TransformHelper.transformPathObject(annotation, transformer, true)
}
addObjects(transformed_annotations)

def transformed_detections = all_detections.collect { detection ->
    def transformed_detection = TransformHelper.transformPathObject(detection, transformer, false)
}
addObjects(transformed_detections)




fireHierarchyUpdate()




