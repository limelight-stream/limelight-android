/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

public abstract class VirtualControllerElement extends View {
    protected static boolean _PRINT_DEBUG_INFORMATION = false;

    public static final int EID_DPAD = 1;
    public static final int EID_LT = 2;
    public static final int EID_RT = 3;
    public static final int EID_LB = 4;
    public static final int EID_RB = 5;
    public static final int EID_A = 6;
    public static final int EID_B = 7;
    public static final int EID_X = 8;
    public static final int EID_Y = 9;
    public static final int EID_BACK = 10;
    public static final int EID_START = 11;
    public static final int EID_LS = 12;
    public static final int EID_RS = 13;
    public static final int EID_LSB = 14;
    public static final int EID_RSB = 15;
    public static final int EID_GDB = 16;

    protected VirtualController virtualController;
    protected final int elementId;

    private final Paint paint = new Paint();

    private int normalColor = 0xF0888888;
    protected int pressedColor = 0xF00000FF;
    private int configMoveColor = 0xF0FF0000;
    private int configResizeColor = 0xF0FF00FF;
    private int configSelectedColor = 0xF000FF00;
    private int configDisabledColor = 0xF0AAAAAA;

    protected int startSize_x;
    protected int startSize_y;

    float position_pressed_x = 0;
    float position_pressed_y = 0;

    private enum Mode {
        Normal,
        Resize,
        Move
    }

    private Mode currentMode = Mode.Normal;

    public boolean enabled = true;

    protected VirtualControllerElement(VirtualController controller, Context context, int elementId) {
        super(context);

        this.virtualController = controller;
        this.elementId = elementId;
    }

    protected void moveElement(int pressed_x, int pressed_y, int x, int y) {
        int newPos_x = (int) getX() + x - pressed_x;
        int newPos_y = (int) getY() + y - pressed_y;

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();

        layoutParams.leftMargin = newPos_x > 0 ? newPos_x : 0;
        layoutParams.topMargin = newPos_y > 0 ? newPos_y : 0;
        layoutParams.rightMargin = 0;
        layoutParams.bottomMargin = 0;

        requestLayout();
    }

    protected void resizeElement(int pressed_x, int pressed_y, int width, int height) {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();

        int newHeight = height + (startSize_y - pressed_y);
        int newWidth = width + (startSize_x - pressed_x);

        layoutParams.height = newHeight > 20 ? newHeight : 20;
        layoutParams.width = newWidth > 20 ? newWidth : 20;

        requestLayout();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        onElementDraw(canvas);

        if (currentMode != Mode.Normal) {
            paint.setColor(configSelectedColor);
            paint.setStrokeWidth(getDefaultStrokeWidth());
            paint.setStyle(Paint.Style.STROKE);

            canvas.drawRect(paint.getStrokeWidth(), paint.getStrokeWidth(),
                    getWidth()-paint.getStrokeWidth(), getHeight()-paint.getStrokeWidth(),
                    paint);
        }

        super.onDraw(canvas);
    }

    /*
    protected void actionShowNormalColorChooser() {
        AmbilWarnaDialog colorDialog = new AmbilWarnaDialog(getContext(), normalColor, true, new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onCancel(AmbilWarnaDialog dialog)
            {}

            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                normalColor = color;
                invalidate();
            }
        });
        colorDialog.show();
    }

    protected void actionShowPressedColorChooser() {
        AmbilWarnaDialog colorDialog = new AmbilWarnaDialog(getContext(), normalColor, true, new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onCancel(AmbilWarnaDialog dialog) {
            }

            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                pressedColor = color;
                invalidate();
            }
        });
        colorDialog.show();
    }
    */

    protected void actionEnableMove() {
        currentMode = Mode.Move;
    }

    protected void actionEnableResize() {
        currentMode = Mode.Resize;
    }

    protected  void actionDisableEnableButton(){
        enabled = !enabled;
    }

    protected void actionCancel() {
        currentMode = Mode.Normal;
        invalidate();
    }

    protected int getDefaultColor() {
        if (virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons)
            return configMoveColor;
        else if (virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons)
            return configResizeColor;
        else if (virtualController.getControllerMode() == VirtualController.ControllerMode.DisableEnableButtons)
            return enabled ? configSelectedColor: configDisabledColor;
        else
            return normalColor;
    }

    protected int getDefaultStrokeWidth() {
        DisplayMetrics screen = getResources().getDisplayMetrics();
        return (int)(screen.heightPixels*0.004f);
    }

    protected void showConfigurationDialog() {
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(getContext());

        alertBuilder.setTitle("Configuration");

        CharSequence functions[] = new CharSequence[]{
                "Move",
                "Resize",
            /*election
            "Set n
            Disable color sormal color",
            "Set pressed color",
            */
                "Cancel"
        };

        alertBuilder.setItems(functions, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case 0: { // move
                        actionEnableMove();
                        break;
                    }
                    case 1: { // resize
                        actionEnableResize();
                        break;
                    }
                /*
                case 2: { // set default color
                    actionShowNormalColorChooser();
                    break;
                }
                case 3: { // set pressed color
                    actionShowPressedColorChooser();
                    break;
                }
                */
                    default: { // cancel
                        actionCancel();
                        break;
                    }
                }
            }
        });
        AlertDialog alert = alertBuilder.create();
        // show menu
        alert.show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Ignore secondary touches on controls
        //
        // NB: We can get an additional pointer down if the user touches a non-StreamView area
        // while also touching an OSC control, even if that pointer down doesn't correspond to
        // an area of the OSC control.
        if (event.getActionIndex() != 0) {
            return true;
        }

        if (virtualController.getControllerMode() == VirtualController.ControllerMode.Active) {
            return onElementTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                position_pressed_x = event.getX();
                position_pressed_y = event.getY();
                startSize_x = getWidth();
                startSize_y = getHeight();

                if (virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons)
                    actionEnableMove();
                else if (virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons)
                    actionEnableResize();
                else if (virtualController.getControllerMode() == VirtualController.ControllerMode.DisableEnableButtons)
                    actionDisableEnableButton();

                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                switch (currentMode) {
                    case Move: {
                        moveElement(
                                (int) position_pressed_x,
                                (int) position_pressed_y,
                                (int) event.getX(),
                                (int) event.getY());
                        break;
                    }
                    case Resize: {
                        resizeElement(
                                (int) position_pressed_x,
                                (int) position_pressed_y,
                                (int) event.getX(),
                                (int) event.getY());
                        break;
                    }
                    case Normal: {
                        break;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                actionCancel();
                return true;
            }
            default: {
            }
        }
        return true;
    }

    abstract protected void onElementDraw(Canvas canvas);

    abstract public boolean onElementTouchEvent(MotionEvent event);

    protected static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            System.out.println(text);
        }
    }

    public void setColors(int normalColor, int pressedColor) {
        this.normalColor = normalColor;
        this.pressedColor = pressedColor;

        invalidate();
    }


    public void setOpacity(int opacity) {
        int hexOpacity = opacity * 255 / 100;
        this.normalColor = (hexOpacity << 24) | (normalColor & 0x00FFFFFF);
        this.pressedColor = (hexOpacity << 24) | (pressedColor & 0x00FFFFFF);

        invalidate();
    }

    protected final float getPercent(float value, float percent) {
        return value / 100 * percent;
    }

    protected final int getCorrectWidth() {
        return getWidth() > getHeight() ? getHeight() : getWidth();
    }


    public JSONObject getConfiguration() throws JSONException {
        JSONObject configuration = new JSONObject();

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();

        configuration.put("LEFT", layoutParams.leftMargin);
        configuration.put("TOP", layoutParams.topMargin);
        configuration.put("WIDTH", layoutParams.width);
        configuration.put("HEIGHT", layoutParams.height);

        configuration.put("ENABLED", enabled);

        return configuration;
    }

    public void loadConfiguration(JSONObject configuration) throws JSONException {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();

        layoutParams.leftMargin = configuration.getInt("LEFT");
        layoutParams.topMargin = configuration.getInt("TOP");
        layoutParams.width = configuration.getInt("WIDTH");
        layoutParams.height = configuration.getInt("HEIGHT");

        enabled = configuration.getBoolean("ENABLED");
        setVisibility(enabled ? VISIBLE: INVISIBLE);

        requestLayout();
    }
}
