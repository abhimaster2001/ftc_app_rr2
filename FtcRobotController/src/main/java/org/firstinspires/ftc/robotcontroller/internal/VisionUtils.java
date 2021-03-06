package org.firstinspires.ftc.robotcontroller.internal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;

import com.vuforia.CameraCalibration;
import com.vuforia.Image;
import com.vuforia.Matrix34F;
import com.vuforia.Tool;
import com.vuforia.Vec3F;

import org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix;
import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.VuforiaLocalizer;
import org.firstinspires.ftc.robotcore.external.navigation.VuforiaTrackableDefaultListener;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.Arrays;

/**
 * Created by Tycho on 5/7/2017.
 * This is a bit messy, but needed to pull some of our VisionUtils into the RobotController context because I don't know how
 * to call into TeamCode methods from here, and user-input event handling, like onTouch(), needs to happen in the activity context
 * and not the opmode context
 */

public class VisionUtils {


        public final static int NOT_VISIBLE = 0;

        public final static int BEACON_BLUE_RED = 1;//blue left, red right
        public final static int BEACON_RED_BLUE = 2;//blue right, red left

        //hsv blue beacon range colours
        //DON'T CHANGE THESE NUMBERS
        public final static Scalar BEACON_BLUE_LOW = new Scalar(108, 0, 220);
        public final static Scalar BEACON_BLUE_HIGH = new Scalar(178, 255, 255);

        public final static Scalar OTHER_BLUE_LOW = new Scalar(105, 120, 110);
        public final static Scalar OTHER_BLUE_HIGH = new Scalar(185, 255, 255);
        public final static Scalar OTHER_RED_LOW = new Scalar(222, 101, 192);
        public final static Scalar OTHER_RED_HIGH = new Scalar(47, 251, 255);

        public static Mat bitmapToMat (Bitmap bit, int cvType) {
            Mat newMat = new Mat(bit.getHeight(), bit.getWidth(), cvType);

            Utils.bitmapToMat(bit, newMat);

            return newMat;
        }

    public static Scalar convertScalarHsv2Rgba(Scalar hsvColor) {
        Mat pointMatRgba = new Mat();
        Mat pointMatHsv = new Mat(1, 1, CvType.CV_8UC3, hsvColor);
        Imgproc.cvtColor(pointMatHsv, pointMatRgba, Imgproc.COLOR_HSV2RGB_FULL, 4);

        return new Scalar(pointMatRgba.get(0, 0));
    }

        public Mat applyMask(Mat img, Scalar low, Scalar high) {

            Mat mask = new Mat(img.size(), CvType.CV_8UC3);

            if (high.val[0] < low.val[0]) {
                Scalar lowMed = new Scalar(255, high.val[1], high.val[2]);
                Scalar medHigh = new Scalar(0, low.val[1], low.val[2]);

                Mat maskLow = new Mat(img.size(), CvType.CV_8UC3);
                Mat maskHigh = new Mat(img.size(), CvType.CV_8UC3);

                Core.inRange(img, low, lowMed, maskLow);
                Core.inRange(img, medHigh, high, maskHigh);

                Core.bitwise_or(maskLow, maskHigh, mask);
            } else {
                Core.inRange(img, low, high, mask);
            }//else

            return mask;
        }




        public int getBeaconConfig(Image img, VuforiaTrackableDefaultListener beacon, CameraCalibration camCal) {

            OpenGLMatrix pose = beacon.getRawPose();

            if (pose != null && img != null && img.getPixels() != null) {

                Matrix34F rawPose = new Matrix34F();
                float[] poseData = Arrays.copyOfRange(pose.transposed().getData(), 0, 12);

                rawPose.setData(poseData);

                //calculating pixel coordinates of beacon corners
                float[][] corners = new float[4][2];

                corners[0] = Tool.projectPoint(camCal, rawPose, new Vec3F(-127, 276, 0)).getData(); //upper left of beacon
                corners[1] = Tool.projectPoint(camCal, rawPose, new Vec3F(127, 276, 0)).getData(); //upper right of beacon
                corners[2] = Tool.projectPoint(camCal, rawPose, new Vec3F(127, -92, 0)).getData(); //lower right of beacon
                corners[3] = Tool.projectPoint(camCal, rawPose, new Vec3F(-127, -92, 0)).getData(); //lower left of beacon

                //getting camera image...
                Bitmap bm = Bitmap.createBitmap(img.getWidth(), img.getHeight(), Bitmap.Config.RGB_565);
                bm.copyPixelsFromBuffer(img.getPixels());

                //turning the corner pixel coordinates into a proper bounding box
                Mat crop = bitmapToMat(bm, CvType.CV_8UC3);
                float x = Math.min(Math.min(corners[1][0], corners[3][0]), Math.min(corners[0][0], corners[2][0]));
                float y = Math.min(Math.min(corners[1][1], corners[3][1]), Math.min(corners[0][1], corners[2][1]));
                float width = Math.max(Math.abs(corners[0][0] - corners[2][0]), Math.abs(corners[1][0] - corners[3][0]));
                float height = Math.max(Math.abs(corners[0][1] - corners[2][1]), Math.abs(corners[1][1] - corners[3][1]));


                //make sure our bounding box doesn't go outside of the image
                //OpenCV doesn't like that...
                x = Math.max(x, 0);
                y = Math.max(y, 0);
                width = (x + width > crop.cols())? crop.cols() - x : width;
                height = (y + height > crop.rows())? crop.rows() - y : height;

                //cropping bounding box out of camera image
                final Mat cropped = new Mat(crop, new Rect((int) x, (int) y, (int) width, (int) height));

                //filtering out non-beacon-blue colours in HSV colour space
                Imgproc.cvtColor(cropped, cropped, Imgproc.COLOR_RGB2HSV_FULL);

                //get filtered mask
                //if pixel is within acceptable blue-beacon-colour range, it's changed to white.
                //Otherwise, it's turned to black
                Mat mask = new Mat();

                Core.inRange(cropped, BEACON_BLUE_LOW, BEACON_BLUE_HIGH, mask);
                Moments mmnts = Imgproc.moments(mask, true);

                //calculating centroid of the resulting binary mask via image moments
                Log.i("CentroidX", "" + ((mmnts.get_m10() / mmnts.get_m00())));
                Log.i("CentroidY", "" + ((mmnts.get_m01() / mmnts.get_m00())));

                //checking if blue either takes up the majority of the image (which means the beacon is all blue)
                //or if there's barely any blue in the image (which means the beacon is all red or off)
//            if (mmnts.get_m00() / mask.total() > 0.8) {
//                return VortexUtils.BEACON_ALL_BLUE;
//            } else if (mmnts.get_m00() / mask.total() < 0.1) {
//                return VortexUtils.BEACON_NO_BLUE;
//            }//elseif

                //Note: for some reason, we end up with a image that is rotated 90 degrees
                //if centroid is in the bottom half of the image, the blue beacon is on the left
                //if the centroid is in the top half, the blue beacon is on the right
                if ((mmnts.get_m10() / mmnts.get_m00()) < cropped.cols() / 2) {
                    return VisionUtils.BEACON_RED_BLUE;
                } else {
                    return VisionUtils.BEACON_BLUE_RED;
                }//else
            }//if

            return VisionUtils.NOT_VISIBLE;
        }//getBeaconConfig


        public static Bitmap getBitmapCopyFromView(View v)
        {
            Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            v.draw(c);
            return b;
        }



        @Nullable
        public Image getImageFromFrame(VuforiaLocalizer.CloseableFrame frame, int format) {

            long numImgs = frame.getNumImages();
            for (int i = 0; i < numImgs; i++) {
                if (frame.getImage(i).getFormat() == format) {
                    return frame.getImage(i);
                }//if
            }//for

            return null;
        }



}
