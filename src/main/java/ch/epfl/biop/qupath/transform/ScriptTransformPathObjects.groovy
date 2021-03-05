import ch.epfl.biop.qupath.transform.TransformHelper

// Gets all annotations from the file
def all_annotations = getAnnotationObjects().findAll { true }//it.getPathClass() == getPathClass("Training") }

def all_detections = new ArrayList(getDetectionObjects().findAll{ true })

// Opens BigWarp landmark file
def directory = "src\\test\\resources\\"
def fileName = "landmarks-v1.csv"
def bwLandmarkFile = new File(directory+fileName)
def rt = TransformHelper.realTransformFromBigWarpFile(bwLandmarkFile, false)

// Makes JTS transformer

def transformer = TransformHelper.getJTSFilter(rt)

// Transforms all annotations
all_annotations.each{ annotation ->
    def transformed_annotation = TransformHelper.transformPathObject(annotation, transformer, true)
    addObject(transformed_annotation)
}

all_detections.each{ detection ->
    def transformed_detection = TransformHelper.transformPathObject(detection, transformer, false)
    addObject(transformed_detection)
}

fireHierarchyUpdate()