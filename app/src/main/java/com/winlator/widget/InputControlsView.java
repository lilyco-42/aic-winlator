package com.winlator.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.ExternalControllerBinding;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.math.Mathf;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorFilter lightColorFilter;
    private ColorFilter darkColorFilter;
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private boolean moveElement = false;
    private int snappingSize;
    private float startX;
    private float startY;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private XServer xServer;
    private final Bitmap[] icons = new Bitmap[18];
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    private boolean showTouchscreenControls = true;
    // 「游戏画面区」的宽度（像素）。用于把触屏控件摆进画面内，而不是铺满全屏。
    //
    // 背景：游戏画面按 container.screenSize 缩放后**居中**显示，屏幕比 16:9 更宽时
    // 两侧会留黑边（实测 2376x1080 → 画面只有 1920 宽，两侧各 228px 黑边）。
    // 如果控件按全屏宽归一化，贴边的按键就会被推进黑边区，拇指够不到。
    // 0 表示未知 —— 此时退回「按全屏宽」的旧行为。
    private int viewportWidth = 0;

    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0x00000000);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    /**
     * 游戏「画面区」的宽度（像素）；0 = 未知（按全屏宽处理）。
     * 触屏控件按这个宽度归一化，确保落在画面内而不是被推到黑边。
     */
    public int getViewportWidth() {
        return viewportWidth > 0 ? viewportWidth : getMaxWidth();
    }

    public void setViewportWidth(int viewportWidth) {
        this.viewportWidth = Math.max(0, viewportWidth);
    }

    /**
     * 按「游戏画面等比缩放后居中显示」算出画面区的宽度（像素）。
     *
     * 背景：游戏画面按 container.screenSize（如 1280x720）等比缩放后居中显示。
     * 屏幕比 16:9 更宽时（实测一加 15 横屏 2376x1080 = 2.2:1）画面只占中间约
     * 1920px，左右各有约 228px 黑边。触屏控件的 x 是 0~1 归一化值，若乘「全屏宽」，
     * 贴右边设计的按键（x≈0.95）会被推到物理边缘甚至落进黑边，拇指够不到。
     *
     * 算法：屏幕高就是画面的高（宽屏时纵向填满），所以
     *     画面宽 = 短边 × (容器宽 / 容器高)
     * 再夹到屏幕宽以内（防竖屏，或容器比屏幕更宽时越界）。
     *
     * ⚠ 静态方法，供 XServerDisplayActivity（游玩）与 ControlsEditorActivity（编辑）
     * 共用 —— 两处必须得到完全一样的值，否则「在编辑器里摆好的位置」进游戏会变。
     *
     * @param screenSize 容器的 "宽x高"（如 "1280x720"），null/格式错则返回 0
     */
    public static int computeViewportWidth(Context context, String screenSize) {
        if (screenSize == null) return 0;
        String[] parts = screenSize.split("x");
        if (parts.length != 2) return 0;
        try {
            final float gameW = Float.parseFloat(parts[0].trim());
            final float gameH = Float.parseFloat(parts[1].trim());
            if (gameW <= 0 || gameH <= 0) return 0;

            final int screenW = context.getResources().getDisplayMetrics().widthPixels;
            final int screenH = context.getResources().getDisplayMetrics().heightPixels;
            final float aspect = gameW / gameH;
            final int longSide = Math.max(screenW, screenH);
            final int shortSide = Math.min(screenW, screenH);
            // 横屏：画面宽 = 短边(高) × 宽高比；竖屏：画面宽 = 短边(宽)
            float viewportWidth = longSide > shortSide ? shortSide * aspect : shortSide;
            viewportWidth = Math.min(viewportWidth, longSide);
            return (int) viewportWidth;
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = width / 100;
        readyToDraw = true;

        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (profile != null) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            List<ControlElement> elements = profile.getElements();
            if (touchpadView != null && elements.isEmpty()) touchpadView.setPointerButtonRightEnabled(true);
            if (showTouchscreenControls) for (ControlElement element : elements) element.draw(canvas);
        }

        super.onDraw(canvas);
    }

    private void drawGrid(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xff000000);
        canvas.drawColor(Color.BLACK);

        paint.setAntiAlias(false);
        paint.setColor(0xff303030);

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i < width; i += snappingSize) {
            canvas.drawLine(i, 0, i, height, paint);
            canvas.drawLine(0, i, width, i, paint);
        }

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(0xff424242);

        for (int i = 0; i < width; i += snappingSize * 2) {
            canvas.drawLine(cx, i, cx, i + snappingSize, paint);
            canvas.drawLine(i, cy, i + snappingSize, cy, paint);
        }

        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xffc62828);

        paint.setAntiAlias(false);
        canvas.drawLine(0, cursor.y, getMaxWidth(), cursor.y, paint);
        canvas.drawLine(cursor.x, 0, cursor.x, getMaxHeight(), paint);

        paint.setAntiAlias(true);
    }

    public synchronized boolean addElement() {
        if (editMode && profile != null) {
            ControlElement element = new ControlElement(this);
            element.setX(cursor.x);
            element.setY(cursor.y);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            profile.removeElement(selectedElement);
            selectedElement = null;
            profile.save();
            invalidate();
            return true;
        }
        else return false;
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.setSelected(false);
        }
    }

    private void selectElement(ControlElement element) {
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    public synchronized void setProfile(ControlsProfile profile) {
        if (profile != null) {
            this.profile = profile;
            deselectAllElements();
        }
        else this.profile = null;
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                if (element.containsPoint(x, y)) return element;
            }
        }
        return null;
    }

    public Paint getPaint() {
        return paint;
    }

    public ColorFilter getLightColorFilter() {
        if (lightColorFilter == null) lightColorFilter = new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN);
        return lightColorFilter;
    }

    public ColorFilter getDarkColorFilter() {
        if (darkColorFilter == null) darkColorFilter = new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.SRC_IN);
        return darkColorFilter;
    }

    public TouchpadView getTouchpadView() {
        return touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
    }

    public XServer getXServer() {
        return xServer;
    }

    public void setXServer(XServer xServer) {
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() {
        return (int)Mathf.roundTo(getWidth(), snappingSize);
    }

    public int getMaxHeight() {
        return (int)Mathf.roundTo(getHeight(), snappingSize);
    }

    private void createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            final float cursorSpeed = profile.getCursorSpeed();
            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    xServer.injectPointerMoveDelta((int)(mouseMoveOffset.x * 10 * cursorSpeed), (int)(mouseMoveOffset.y * 10 * cursorSpeed));
                }
            }, 0, 1000 / 60);
        }
    }

    private void processJoystickInput(ExternalController controller) {
        ExternalControllerBinding controllerBinding;
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        GamepadState state = controller.getGamepadState();
        final float[] values = {state.thumbLX, state.thumbLY, state.thumbRX, state.thumbRY, state.getDPadX(), state.getDPadY()};

        for (byte i = 0; i < axes.length; i++) {
            if (Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i])));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), true, values[i]);
            }
            else {
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) 1));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), false, values[i]);
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte)-1));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), false, values[i]);
            }
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!editMode && profile != null) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                GamepadState state = controller.getGamepadState();
                ExternalControllerBinding controllerBinding;
                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), state.isPressed(ExternalController.IDX_BUTTON_L2));

                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), state.isPressed(ExternalController.IDX_BUTTON_R2));

                processJoystickInput(controller);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode && readyToDraw) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    startX = event.getX();
                    startY = event.getY();

                    ControlElement element = intersectElement(startX, startY);
                    moveCursor = true;
                    moveElement = false;
                    if (element != null) {
                        offsetX = startX - element.getX();
                        offsetY = startY - element.getY();
                        moveCursor = false;
                    }

                    selectElement(element);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (selectedElement != null) {
                        float dx = Math.abs(event.getX() - startX);
                        float dy = Math.abs(event.getY() - startY);

                        if (dx >= TouchpadView.MAX_TAP_TRAVEL_DISTANCE || dy >= TouchpadView.MAX_TAP_TRAVEL_DISTANCE) moveElement = true;

                        if (moveElement) {
                            selectedElement.setX((int)Mathf.roundTo(event.getX() - offsetX, snappingSize));
                            selectedElement.setY((int)Mathf.roundTo(event.getY() - offsetY, snappingSize));
                            invalidate();
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    if (selectedElement != null && profile != null && moveElement) profile.save();
                    if (moveCursor) cursor.set((int)Mathf.roundTo(event.getX(), snappingSize), (int)Mathf.roundTo(event.getY(), snappingSize));
                    invalidate();
                    break;
                }
            }
        }

        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);

                    touchpadView.setPointerButtonLeftEnabled(true);
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) handled = true;
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON && element.getLastBindingIndex() == 0) {
                            touchpadView.setPointerButtonLeftEnabled(false);
                        }
                    }
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int movePointerId = event.getPointerId(i);

                        handled = false;
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(movePointerId, x, y)) handled = true;
                        }
                        if (!handled) touchpadView.onTouchEvent(event);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);
                    for (ControlElement element : profile.getElements()) if (element.handleTouchUp(pointerId, x, y)) handled = true;
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
                }
            }
        }
        return true;
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null) {
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(event.getKeyCode());
                if (controllerBinding != null) {
                    int action = event.getAction();

                    if (action == KeyEvent.ACTION_DOWN) {
                        handleInputEvent(controllerBinding.getBinding(), true);
                    }
                    else if (action == KeyEvent.ACTION_UP) {
                        handleInputEvent(controllerBinding.getBinding(), false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public void handleInputEvent(Binding[] bindings, boolean isActionDown) {
        for (Binding binding : bindings) {
            if (binding != Binding.NONE) handleInputEvent(binding, isActionDown, 0);
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(binding, isActionDown, 0);
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        if (binding.isGamepad()) {
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            GamepadState state = profile.getGamepadState();

            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= 11) {
                state.setPressed(buttonIdx, isActionDown);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                state.thumbLY = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                state.thumbLX = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                state.thumbRY = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                state.thumbRX = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                     binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = isActionDown;
            }

            if (winHandler != null) winHandler.gamepadHandler.sendGamepadState(profile);
        }
        else {
            if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                mouseMoveOffset.x = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_LEFT ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                mouseMoveOffset.y = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_UP ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else if (binding.keycode.isCustomKey()) {
                if (!isActionDown) handleCommandKeyEvent(binding);
            }
            else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonPress(pointerButton);
                    }
                    else xServer.injectKeyPress(binding.keycode);
                }
                else {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonRelease(pointerButton);
                    }
                    else xServer.injectKeyRelease(binding.keycode);
                }
            }
        }
    }

    public Bitmap getIcon(byte id) {
        if (icons[id] == null) {
            Context context = getContext();
            try (InputStream is = context.getAssets().open("inputcontrols/icons/"+id+".png")) {
                icons[id] = BitmapFactory.decodeStream(is);
            }
            catch (IOException e) {}
        }
        return icons[id];
    }

    private void handleCommandKeyEvent(Binding binding) {
        Context context = getContext();
        if (binding == Binding.KEY_VOL_UP || binding == Binding.KEY_VOL_DOWN) {
            AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if (binding == Binding.KEY_VOL_UP) {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND);
            }
            else audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND);
        }
    }
}
