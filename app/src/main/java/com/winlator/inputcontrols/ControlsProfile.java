package com.winlator.inputcontrols;

import android.content.Context;

import androidx.annotation.NonNull;

import com.winlator.core.FileUtils;
import com.winlator.widget.InputControlsView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ControlsProfile implements Comparable<ControlsProfile>, GamepadSlot {
    public final int id;
    private String name;
    private float cursorSpeed = 1.0f;
    private boolean disableMouseInput = false;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(elements);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean virtualGamepad = false;
    private final Context context;
    private GamepadState gamepadState;
    private GamepadVibration gamepadVibration;

    public ControlsProfile(Context context, int id) {
        this.context = context;
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public short getVendorId() {
        return 0x0001;
    }

    @Override
    public short getProductId() {
        return 0x0001;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getCursorSpeed() {
        return cursorSpeed;
    }

    public void setCursorSpeed(float cursorSpeed) {
        this.cursorSpeed = cursorSpeed;
    }

    public boolean isDisableMouseInput() {
        return disableMouseInput;
    }

    public void setDisableMouseInput(boolean disableMouseInput) {
        this.disableMouseInput = disableMouseInput;
    }

    public boolean isVirtualGamepad() {
        return virtualGamepad;
    }

    @Override
    public GamepadState getGamepadState() {
        if (gamepadState == null) gamepadState = new GamepadState();
        return gamepadState;
    }

    @Override
    public GamepadVibration getGamepadVibration() {
        if (gamepadVibration == null) gamepadVibration = new GamepadVibration(context);
        return gamepadVibration;
    }

    public ExternalController addController(String id) {
        ExternalController controller = getController(id);
        if (controller == null) controllers.add(controller = ExternalController.getController(id));
        controllersLoaded = true;
        return controller;
    }

    public void removeController(ExternalController controller) {
        if (!controllersLoaded) loadControllers();
        controllers.remove(controller);
    }

    public ExternalController getController(String id) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public ExternalController getController(int deviceId) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getDeviceId() == deviceId) return controller;
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(ControlsProfile o) {
        return Integer.compare(id, o.id);
    }

    public boolean isElementsLoaded() {
        return elementsLoaded;
    }

    public void save() {
        File file = getProfileFile(context, id);

        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("cursorSpeed", Float.valueOf(cursorSpeed));
            if (disableMouseInput) data.put("disableMouseInput", disableMouseInput);

            JSONArray elementsJSONArray = new JSONArray();
            if (!elementsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                elementsJSONArray = profileJSONObject.getJSONArray("elements");
            }
            else for (ControlElement element : elements) elementsJSONArray.put(element.toJSONObject());
            data.put("elements", elementsJSONArray);

            JSONArray controllersJSONArray = new JSONArray();
            if (!controllersLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("controllers")) controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            }
            else {
                for (ExternalController controller : controllers) {
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
                }
            }
            if (controllersJSONArray.length() > 0) data.put("controllers", controllersJSONArray);

            FileUtils.writeString(file, data.toString());
        }
        catch (JSONException e) {}
    }

    public static File getProfileFile(Context context, int id) {
        return new File(InputControlsManager.getProfilesDir(context), "controls-"+id+".icp");
    }

    public void addElement(ControlElement element) {
        elements.add(element);
        elementsLoaded = true;
    }

    public void removeElement(ControlElement element) {
        elements.remove(element);
        elementsLoaded = true;
    }

    public List<ControlElement> getElements() {
        return immutableElements;
    }

    public boolean isTemplate() {
        return name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<ExternalController> loadControllers() {
        controllers.clear();
        controllersLoaded = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return controllers;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (!profileJSONObject.has("controllers")) return controllers;
            JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            for (int i = 0; i < controllersJSONArray.length(); i++) {
                JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
                String id = controllerJSONObject.getString("id");
                ExternalController controller = new ExternalController();
                controller.setId(id);
                controller.setName(controllerJSONObject.getString("name"));

                JSONArray controllerBindingsJSONArray = controllerJSONObject.getJSONArray("controllerBindings");
                for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
                    JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
                    ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
                    controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
                    controllerBinding.setBinding(Binding.fromString(controllerBindingJSONObject.getString("binding")));
                    controller.addControllerBinding(controllerBinding);
                }
                controllers.add(controller);
            }
            controllersLoaded = true;
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
        return controllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        elements.clear();
        elementsLoaded = false;
        virtualGamepad = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
            for (int i = 0; i < elementsJSONArray.length(); i++) {
                JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);

                ControlElement element = null;
                if (inputControlsView != null) {
                    element = new ControlElement(inputControlsView);
                    element.setType(ControlElement.Type.valueOf(elementJSONObject.getString("type")));
                    element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
                    element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
                    // ── 坐标换算：按「画面区」而不是「全屏」来摆控件 ──────────────
                    //
                    // 起因：布局文件里的 x/y 是 0~1 的归一化值，原本直接乘全屏宽高。
                    // 但游戏画面是按 container 的 screenSize(如 1280x720) 缩放后
                    // **居中**显示的；屏幕比 16:9 更宽时（例如 2376x1080 = 2.2:1），
                    // 画面两侧有大片黑边。
                    //
                    // 后果：原本贴右边的按键（x=0.955）会被推到物理边缘 ——
                    // 换算到画面坐标系里甚至 > 1.0（跑到画面外的黑边区），
                    // 拇指根本够不到。实测就是这样，「取消」按钮落在画面外。
                    //
                    // 修法：把归一化 x 先映射到「画面在屏幕上占的那一段」，
                    // 用 viewportWidth/getMaxWidth() 作为可用的横向上限，
                    // 并把整段**居中**。这样一份布局在任何屏幕比例下都落在画面内，
                    // 且拇指可达性一致（不再随屏幕变宽而被推远）。
                    //
                    // y 不需要处理：画面在纵向总是占满（宽屏时纵向填满），
                    // 且纵向偏移会破坏「底部按键在底部」的直觉。
                    final float maxWidth = inputControlsView.getMaxWidth();
                    final float maxHeight = inputControlsView.getMaxHeight();
                    final float viewportWidth = inputControlsView.getViewportWidth();
                    final float xNorm = (float)elementJSONObject.getDouble("x");
                    final float xOffset = Math.max(0f, (maxWidth - viewportWidth) / 2f);
                    element.setX((int)(xOffset + xNorm * Math.min(maxWidth, viewportWidth)));
                    element.setY((int)(elementJSONObject.getDouble("y") * maxHeight));
                    element.setScale((float)elementJSONObject.getDouble("scale"));
                    element.setText(elementJSONObject.getString("text"));
                    element.setIconId(elementJSONObject.getInt("iconId"));
                    if (elementJSONObject.has("range")) element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
                    if (elementJSONObject.has("orientation")) element.setOrientation((byte)elementJSONObject.getInt("orientation"));
                    if (elementJSONObject.has("mouseMoveMode")) element.setMouseMoveMode(true);
                    if (elementJSONObject.has("opacity")) element.setOpacity((float)elementJSONObject.getDouble("opacity"));
                }

                boolean hasGamepadBinding = true;
                JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
                for (int j = 0; j < bindingsJSONArray.length(); j++) {
                    Binding binding = Binding.fromString(bindingsJSONArray.getString(j));
                    if (element != null) element.setBindingAt(j, Binding.fromString(bindingsJSONArray.getString(j)));
                    if (!binding.isGamepad()) hasGamepadBinding = false;
                }

                if (!virtualGamepad && hasGamepadBinding) virtualGamepad = true;
                if (element != null) elements.add(element);
            }
            elementsLoaded = true;
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
