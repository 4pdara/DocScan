// Stand-ins for the Android parts LiveDetection touches, so it runs on a desktop JVM. The replay always has the
// network installed, so ImagePipeline.detectGray (the path without it) is never called.
package com.docscan.rebuild.imaging
object ImagePipeline { fun detectGray(g: org.opencv.core.Mat, c: org.opencv.core.Mat): com.docscan.rebuild.model.Frame? = error("not in the replay") }
