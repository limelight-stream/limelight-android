package com.limelight.binding.input.touch;

import android.view.MotionEvent;
import android.util.Log;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Iterator;

class ScreenUtils {
    public static float getScreenWidth() {
        DisplayMetrics displayMetrics = Resources.getSystem().getDisplayMetrics();
        return displayMetrics.widthPixels;
    }

    public static float getScreenHeight() {
        DisplayMetrics displayMetrics = Resources.getSystem().getDisplayMetrics();
        return displayMetrics.heightPixels;
    }
}


/**
 * Pointer oriented class
 *
 *
 *  to store additional pointer info updated from Android MotionEvent object
 *  (stored in NativeTouchContext.Pointer instance).
 *  Provides some methods to manipulate pointer coordinates (enhanced touch) before sending to Sunshine server,
 */
public class NativeTouchContext {
    /**
     * Defines a (2*INTIAL_ZONE_PIXELS)^2 square flat region for long press jitter elimination.
     * Config read from prefConfig in Game class
     */
    public static float INTIAL_ZONE_PIXELS = 0f;

    /**
     * Set true to send relative(manipulated) coords to Sunshine server.
     * Config read from prefConfig in Game class
     */
    public static boolean ENABLE_ENHANCED_TOUCH = true;

    /**
     * 1 means enhanced-touch zone on the right side, -1 on the left side.
     * Config read from prefConfig in Game class
     */
    public static int ENHANCED_TOUCH_ON_RIGHT = 1;

    /**
     * Defines where to divide native-touch & enchanced-touch zones,
     * < 0.5f means divide from a point on the left, >0.5f means right.
     * Config read from prefConfig in Game class
     */
    public static float ENHANCED_TOUCH_ZONE_DIVIDER = 0.5f;

    /**
     * Factor to scale pointer velocity within enhanced touch zone,
     * Config read from prefConfig in Game class
     */
    public static float POINTER_VELOCITY_FACTOR = 1.0f;

    /**
     * Fixed horizontal velocity (in pixels) within enhanced touch zone
     * Config read from prefConfig in Game class
     */
    // public static float POINTER_FIXED_X_VELOCITY = 8f;

    /**
     * Object to store, update info & manipulate coordinates for each pointer.
     * An ArrayList of NativeTouchContext.Pointer instances is created in Game Class for all active pointers.
     */
    public static class Pointer{
        /**
         * poinerId, not pointerIndex.
         * Use pointerId because it's consistent during the whole pointer lifecycle.
         */
        private int pointerId;

        /**
         * Use MotionEvent.PointerCoords from Android SDK to store coordinates.
         */
        private MotionEvent.PointerCoords initialCoords = new MotionEvent.PointerCoords(); //First contact coords of a pointer.
        private MotionEvent.PointerCoords latestCoords = new MotionEvent.PointerCoords(); //Latest coords of a pointer updated from MotionEvent provided by Android System.
        private MotionEvent.PointerCoords previousCoords = new MotionEvent.PointerCoords(); // previous Coords of a pointer. will be updated in updatePointerCoords().
        private MotionEvent.PointerCoords latestRelativeCoords = new MotionEvent.PointerCoords(); // Coords to replace native ones from Android MotionEvents, for manipulating touch control.
        private MotionEvent.PointerCoords previousRelativeCoords = new MotionEvent.PointerCoords(); // Coords to replace native ones from Android MotionEvents, for manipulating touch control.

        /**
         * DeltaX & DeltaY between to 2 onTouch() callbacks.
         */
        private float velocityX;
        private float velocityY;


        /**
         * Flipped to true when pointer moves out of (2*INTIAL_ZONE_PIXELS)^2 square flat region.
         */
        private boolean pointerLeftInitialZone = false;

        /**
         * Constructor for NativeTouchContext.Pointer.
         * Pointer class instantiated in ACTION_DOWN, ACTION_POINTER_DOWN Condition.
         */
        public Pointer(MotionEvent event) {
            int pointerIndex = event.getActionIndex(); //get the pointerIndex triggers DOWN event.
            this.pointerId = event.getPointerId(pointerIndex); // get pointerId
            event.getPointerCoords(pointerIndex, this.initialCoords);// Fill in initial coords.
            event.getPointerCoords(pointerIndex, this.latestCoords);// Fill in latest coords.
            //if(POINTER_VELOCITY_FACTOR != 1.0f){
            this.latestRelativeCoords.x = this.latestCoords.x;
            this.latestRelativeCoords.y = this.latestCoords.y;
        }
        public int getPointerId(){
            return this.pointerId;
        }

        /**
         * Update native coordinates, relative coordinates & velocity for Pointer instance
         */
        public void updatePointerCoords(MotionEvent event, int pointerIndex){
            this.previousCoords.x = this.latestCoords.x; // assign x, y coords to this.previousCoords only. Other attributes can be ignored.
            this.previousCoords.y = this.latestCoords.y;
            event.getPointerCoords(pointerIndex, this.latestCoords); // update latestCoords from MotionEvent.

            if(POINTER_VELOCITY_FACTOR == 1.0f) {
                this.latestRelativeCoords.x = this.latestCoords.x;
                this.latestRelativeCoords.y = this.latestCoords.y;
            }
            else this.updateRelativeCoords();

            // 固定X速率模式，该模式下仍可用POINTER_VELOCITY_FACTOR调整Y的速率
            // if(POINTER_FIXED_X_VELOCITY != 0f)  this.updateRelativeCoordsFixedXVelocity();

            if(INTIAL_ZONE_PIXELS > 0f) this.flattenLongPressJitter();
            // Log.d("INTIAL_ZONE_PIXELS", ""+INTIAL_ZONE_PIXELS);
        }

        /**
         * Update relative coordinates with velocity scaled by POINTER_VELOCITY_FACTOR
         */
        private void updateRelativeCoords(){
            this.velocityX = this.latestCoords.x - this.previousCoords.x;
            this.velocityY = this.latestCoords.y - this.previousCoords.y;
            this.previousRelativeCoords.x = this.latestRelativeCoords.x;
            this.previousRelativeCoords.y = this.latestRelativeCoords.y;
            this.latestRelativeCoords.x = this.previousRelativeCoords.x + this.velocityX * POINTER_VELOCITY_FACTOR;
            this.latestRelativeCoords.y = this.previousRelativeCoords.y + this.velocityY * POINTER_VELOCITY_FACTOR;
        }

        /**
         * Update relative coordinates with fixed X velocity
         */
        /* private void updateRelativeCoordsFixedXVelocity(){
            this.velocityX = this.latestCoords.x - this.previousCoords.x;
            this.velocityY = this.latestCoords.y - this.previousCoords.y;
            this.previousRelativeCoords.x = this.latestRelativeCoords.x;
            this.previousRelativeCoords.y = this.latestRelativeCoords.y;
            this.latestRelativeCoords.x = this.previousRelativeCoords.x + Math.signum(this.velocityX) * POINTER_FIXED_X_VELOCITY;
            this.latestRelativeCoords.y = this.previousRelativeCoords.y + this.velocityY * POINTER_VELOCITY_FACTOR;
        } */

        /**
         * Judge whether this pointer leaves (2*INTIAL_ZONE_PIXELS)^2 square flat region
         */
        private void checkIfPointerLeaveInitialZone() {
            if (!this.pointerLeftInitialZone) {
                if (Math.abs(this.latestCoords.x - this.initialCoords.x) > INTIAL_ZONE_PIXELS || Math.abs(this.latestCoords.y - this.initialCoords.y) > INTIAL_ZONE_PIXELS) {
                        this.pointerLeftInitialZone = true; //Flips pointerLeftInitialZone to true when pointer moves out of flat region.
                }
            }
        }

        /**
         * Resets latest coords (both native & relative) to initial coords if pointer doesn't leave flat region.
         */
        private void flattenLongPressJitter(){
            this.checkIfPointerLeaveInitialZone();
            // Log.d("INTIAL_ZONE_PIXELS", ""+INTIAL_ZONE_PIXELS);
            if(!this.pointerLeftInitialZone){
                this.latestCoords.x = this.initialCoords.x;
                this.latestCoords.y = this.initialCoords.y;
                this.latestRelativeCoords.x = this.initialCoords.x;
                this.latestRelativeCoords.y = this.initialCoords.y;
                // Log.d("pointerLeftInitialZone", ""+pointerLeftInitialZone);
            }
        }

        /**
         * Judge whether pointer's coords should be manipulated based on its initial coords (first contact location)
         * Only Supports horizontal split (left and right) for now.
         */
        private boolean withinEnhancedTouchZone ()
        {
            // float[] normalizedCoords = new float[] {pointerIntialCoords[0]/ScreenUtils.getScreenWidth(), pointerIntialCoords[1]/ScreenUtils.getScreenHeight()};
            float normalizedX = this.initialCoords.x/ScreenUtils.getScreenWidth();
            return normalizedX * ENHANCED_TOUCH_ON_RIGHT > ENHANCED_TOUCH_ZONE_DIVIDER * ENHANCED_TOUCH_ON_RIGHT;
        }

        public float[] XYCoordSelector(){
                //to judge whether pointer located on enhanced touch zone by its initial coords.
            if (this.withinEnhancedTouchZone()) return new float[] {this.latestRelativeCoords.x,this.latestRelativeCoords.y};
            else return new float[] {this.latestCoords.x,this.latestCoords.y};
        }

        public float getInitialX(){
            return this.initialCoords.x;
        }

        public float getPointerNormalizedInitialX(){
            return this.initialCoords.x / ScreenUtils.getScreenWidth();
        }

        public float getInitialY(){
            return this.initialCoords.y;
        }

        public float getPointerNormalizedInitialY(){
            return this.initialCoords.y / ScreenUtils.getScreenHeight();
        }

        public float getLatestX(){
            return this.latestCoords.x;
        }


        public float getLatestY(){
            return this.latestCoords.y;
        }
        public float getLatestRelativeX(){
            return this.latestRelativeCoords.x;
        }

        public float getLatestRelativeY(){
            return this.latestRelativeCoords.y;
        }


        public float getPointerNormalizedLatestX(){
            return this.latestCoords.x / ScreenUtils.getScreenWidth();
        }

        public float getPointerNormalizedLatestY(){
            return this.latestCoords.y / ScreenUtils.getScreenHeight();
        }

        public void printPointerInitialCoords(){
            Log.d("Initial Coords","Pointer " + this.pointerId + " Coords: X " + this.getInitialX() + " Y " +this.getInitialY());
        }
        public void printPointerLatestCoords(){
            Log.d("Latest Coords","Pointer " + this.pointerId + " Coords: X " + this.getLatestX() + " Y " +this.getLatestY());
        }
        public void printPointerCoordSnapshot(){
            Log.d("Pointer " + this.pointerId, " InitialCoords:" + "[" + this.getInitialX() + ", " + this.getInitialY() + "]" + " LatestCoords:" + "[" + this.getLatestX() + ", " + this.getLatestY() + "]");
        }


    }




    /**
     * The Game class defines an ArrayList for all instances all active pointers.
     * While iterating pointer info in Game class with pointerIndex in ACTION_MOVE condition,
     * this method is called to access additional pointer info from the list by finding a pointerId match,
     * and decides whether the pointer's coords should be manipulated.
     */
    /* public static float[] selectCoordsForPointer(MotionEvent event, int pointerIndex, ArrayList<NativeTouchContext.Pointer> nativeTouchPointers){
        float selectedX = 0f;
        float selectedY = 0f;
        for (NativeTouchContext.Pointer pointer : nativeTouchPointers) {
            if (event.getPointerId(pointerIndex) == pointer.getPointerId()) {
                if(ENABLE_ENHANCED_TOUCH) {
                    //to judge whether pointer located on enhanced touch zone by its initial coords.
                    if (isEnhancedTouchZone(new float[] {pointer.getInitialX(), pointer.getInitialY()})) {
                        selectedX = pointer.getLatestRelativeX();
                        selectedY = pointer.getLatestRelativeY();
                    }
                    else{
                        selectedX = pointer.getLatestX();
                        selectedY = pointer.getLatestY();
                    }
                }
                else{
                    selectedX = pointer.getLatestX();
                    selectedY = pointer.getLatestY();
                }
                break;
            }
        }
        return new float[] {selectedX, selectedY};
    } */


    /**
     * Safely remove Pointer instance from a List in ACTION_POINTER_UP or ACTION_UP condition
     */
    /* public static void safelyRemovePointerFromList(MotionEvent event, ArrayList<NativeTouchContext.Pointer> nativeTouchPointers){
        Iterator<Pointer> iterator = nativeTouchPointers.iterator(); //safely remove pointer handler by iterator.
        while (iterator.hasNext()){
            NativeTouchContext.Pointer pointer = iterator.next();
            if (event.getPointerId(event.getActionIndex()) == pointer.getPointerId()) {
                iterator.remove(); // immediately break when we get a pointerId match
                break;
            }
        }
    } */

    /**
     * Update 1 specific Pointer instance in a List in ACTION_MOVE.
     */
     /* public static void updatePointerInList(MotionEvent event,int pointerIndex, ArrayList<NativeTouchContext.Pointer> nativeTouchPointers) {
        for (NativeTouchContext.Pointer pointer : nativeTouchPointers) {
            if (pointer.getPointerId() == event.getPointerId(pointerIndex)) {
                pointer.updatePointerCoords(event, pointerIndex);
                // handler.doesPointerLeaveInitialZone();
                // Log.d("NativeTouchCoordHandler", "Pointer Left Initial Zone: " + handler.doesPointerLeaveInitialZone());
                // pointer.printPointerCoordSnapshot();
                break; // immediately break when we get a pointerId match (this method update 1 pointer in the list)
            }
        }
    } */
}
