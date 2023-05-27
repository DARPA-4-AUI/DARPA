//DARPA – an end-to-end and generic CV-based solution to
//identify AUIs at run-time and mitigate the risks
//by highlighting the AUIs with run-time UI decoration.
//Copyright (c) [2022] [DARPA-4-AUI]. All rights reserved.

package com.darpa;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MyAccessibilityService extends AccessibilityService {

    private YoloV5Ncnn yolov5ncnn = null;
    private static String TAG = "MyAccessibilityService";
    private Worker worker;
    private List<String> keyWordList = new ArrayList<>(Arrays.asList("开", "開", "领", "抢", "去", "赢", "赚", "奖", "秒杀",
            "安装", "按钮", "半价", "报名", "补贴", "参加", "参与", "查看", "查询", "抽奖", "大奖", "出发", "打开", "点击", "兑换",
            "返现", "福利", "更多", "购买", "观看", "红包", "滑出", "即刻", "加购", "加入", "奖励", "降价", "解锁", "进入", "开启",
            "开奖", "了解", "立即", "领取", "马上", "免单", "免费", "拼单", "前往", "轻滑", "入手", "上滑", "收下", "刷新", "提现",
            "挑战", "跳转", "围观", "现金", "限量", "限时", "详情", "摇动", "摇摇", "页面", "一键", "优惠", "图标", "下载", "滑动",
            "参加", "参与", "兑换", "领取", "抢购", "体验", "咨询", "安装", "中奖", "上滑", "第三方", "去看看", "去拿钱", "去想想",
            "摇一摇", "知道了", "點擊下載", "扭动手机", "前往围观", "向上滑动", "一探究竟", "转动手机", "Open", "open", "OPEN",
            "Enter", "ENTER", "enter", "Continue", "Receive"));

    public static int detectTiming;
    public static boolean isAlertMode = true;
    public static boolean isDefendMode = false;
    private boolean falsePositive = false;
    public static int openTLX = 0;
    public static int openBRX = 0;
    public static int openTLY = 0;
    public static int openBRY = 0;
    private long mLastEventTime;

    private static String lastPackageName = "com.darpa";
    private static String  lastPackageClass = "";
    private boolean jumpFlag = true;
    private static ArrayList<String> systemApks = new ArrayList<>();


    @Override
    public void takeScreenshot(int displayId, @NonNull Executor executor, @NonNull TakeScreenshotCallback callback) {
        super.takeScreenshot(displayId, executor, callback);
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        setServiceInfo(info);
        getSystemApk();
        Log.i("TAG","startService");
        for(String apk:systemApks)
        {
            Log.i("TAG",apk);
        }


        Log.e("MyAccessibilityService test", "onServiceConnected");
        yolov5ncnn = new YoloV5Ncnn();
        boolean ret_init = yolov5ncnn.Init(getAssets());
        if (!ret_init) {
            Log.e("MyAccessibilityService onServiceConnected", "yolov5ncnn Init failed");
            yolov5ncnn = null;
        } else {
            Log.i("MyAccessibilityService onServiceConnected", "yolov5ncnn Init successfully");
        }
        worker = new Worker();
        worker.start();
    }

    @Override
    public void onInterrupt() {
        Log.e("MyAccessibilityService test", "onInterrupt");
        stop();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.e("MyAccessibilityService test", "onUnbind");
        stop();
        return true;
    }

    public volatile boolean stopFlag = false;

    public void stop() {
        stopFlag = true;
    }

    private void handleSingleBoxes(WindowManager wm, ObjGod[] boxesArr, int heightOffset) {
        float crossMaxPro = Integer.MIN_VALUE;
        float skipMaxPro = Integer.MIN_VALUE;
        float openMaxPro = Integer.MIN_VALUE;
        int crossIndex = Integer.MIN_VALUE;
        int skipIndex = Integer.MIN_VALUE;
        int openIndex = Integer.MIN_VALUE;
        for (int i = 0; i < boxesArr.length; i++) {
            float pro = boxesArr[i].prob;
            String label = boxesArr[i].label;
            if (label.equals(StaticData.LABEL_CROSS)) {
                if (pro > crossMaxPro) {
                    crossMaxPro = pro;
                    crossIndex = i;
                }
            } else if (label.equals(StaticData.LABEL_SKIP)) {
                if (pro > skipMaxPro) {
                    skipMaxPro = pro;
                    skipIndex = i;
                }
            } else if (label.equals(StaticData.LABEL_OPEN)) {
                if (!falsePositive) {
                    if (pro > openMaxPro) {
                        openMaxPro = pro;
                        openIndex = i;
                    }
                }
            }
        }
        if (crossIndex != Integer.MIN_VALUE) {
            String label = StaticData.LABEL_CROSS;
            int topLeftX = (int) boxesArr[crossIndex].x;
            int topLeftY = (int) boxesArr[crossIndex].y;
            int bottomRightX = (int) (boxesArr[crossIndex].x + boxesArr[crossIndex].w);
            int bottomRightY = (int) (boxesArr[crossIndex].y + boxesArr[crossIndex].h);
            View view = drawRectangle(wm, topLeftX, topLeftY, bottomRightX, bottomRightY, label, heightOffset);
            viewList.add(view);
        }
        if (skipIndex != Integer.MIN_VALUE) {
            String label = StaticData.LABEL_CROSS;
            int topLeftX = (int) boxesArr[skipIndex].x;
            int topLeftY = (int) boxesArr[skipIndex].y;
            int bottomRightX = (int) (boxesArr[skipIndex].x + boxesArr[skipIndex].w);
            int bottomRightY = (int) (boxesArr[skipIndex].y + boxesArr[skipIndex].h);
            View view = drawRectangle(wm, topLeftX, topLeftY, bottomRightX, bottomRightY, label, heightOffset);
            viewList.add(view);
        }
        if (openIndex != Integer.MIN_VALUE) {
            String label = StaticData.LABEL_OPEN;
            openTLX = (int) boxesArr[openIndex].x;
            openTLY = (int) boxesArr[openIndex].y;
            openBRX = (int) (boxesArr[openIndex].x + boxesArr[openIndex].w);
            openBRY = (int) (boxesArr[openIndex].y + boxesArr[openIndex].h);
            View view = drawRectangle(wm, openTLX, openTLY, openBRX, openBRY, label, heightOffset);
            viewList.add(view);
        }
        falsePositive = false;

    }

    @SuppressLint("HandlerLeak")
    final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
//                removeRectangle(viewList);

                float crossMaxPro = Integer.MIN_VALUE;
                float skipMaxPro = Integer.MIN_VALUE;
                float openMaxPro = Integer.MIN_VALUE;
                int crossIndex = Integer.MIN_VALUE;
                int skipIndex = Integer.MIN_VALUE;
                int openIndex = Integer.MIN_VALUE;

                ObjGod[] boxesArr = (ObjGod[]) msg.obj;
                //graw single type
                for (int i = 0; i < boxesArr.length; i++) {
                    float pro = boxesArr[i].prob;
                    String label = boxesArr[i].label;
                    if (label.equals(StaticData.LABEL_CROSS)) {
                        if (pro > crossMaxPro) {
                            crossMaxPro = pro;
                            crossIndex = i;
                        }
                    } else if (label.equals(StaticData.LABEL_SKIP)) {
                        if (pro > skipMaxPro) {
                            skipMaxPro = pro;
                            skipIndex = i;
                        }
                    } else if (label.equals(StaticData.LABEL_OPEN)) {
                        if (pro > openMaxPro) {
                            openMaxPro = pro;
                            openIndex = i;
                        }
                    }
                }
                if (openIndex != Integer.MIN_VALUE) {
                    int topLeftX = (int) boxesArr[openIndex].x;
                    int topLeftY = (int) boxesArr[openIndex].y;
                    int bottomRightX = (int) (boxesArr[openIndex].x + boxesArr[openIndex].w);
                    int bottomRightY = (int) (boxesArr[openIndex].y + boxesArr[openIndex].h);
                    Log.i("rectangleLocation: ", "topLeftX: " + topLeftX);
                    Log.i("rectangleLocation: ", "topLeftY: " + topLeftY);
                    Log.i("rectangleLocation: ", "bottomRightX: " + bottomRightX);
                    Log.i("rectangleLocation: ", "bottomRightY: " + bottomRightY);
                    int total = listA.size();
                    int index = 0;
                    boolean isFind = false;

                    while (index < total) {
                        AccessibilityNodeInfo node = listA.get(index++);
                        if (node != null) {
                            CharSequence description = node.getContentDescription();
                            CharSequence text = node.getText();
                            Rect rect = new Rect();
                            node.getBoundsInScreen(rect);
                            int openBtnX = rect.centerX();//To get its center point
                            int openBtnY = rect.centerY();
                            //center point is in the rectangle
                            if (topLeftX <= openBtnX && openBtnX <= bottomRightX && topLeftY <= openBtnY && openBtnY <= bottomRightY) {
                                for (String keyword : keyWordList) {
                                    // text or description contains keyword, but not too long
                                    if (text != null && (text.toString().length() <= keyword.length() + 6) && text.toString().contains(keyword)) {
                                        isFind = true;
                                    } else if (description != null && (description.toString().length() <= keyword.length() + 6) && description.toString().contains(keyword)) {
                                        isFind = true;
                                    }
                                    if (isFind) {
                                        // if this node matches our target, stop finding more keywords
                                        Log.i(TAG, "Item containing keywords have been found----------------------------");
                                        Log.d(TAG, "identify keyword = " + keyword);
                                        Log.i(TAG, "Description：" + description);
                                        Log.i(TAG, "Text：" + text);
                                        Log.i(TAG, "Location:" + openBtnX + " " + openBtnY);
                                        break;
                                    }
                                }
                                if (isFind) break;
                            }
                        }
                    }
                    if (!isFind) {
                        falsePositive = true; // WARNING!! It must be "true" to find falsePositive judge.
                        Log.i("It`s falsePositive judge", "Gotcha!");
                    }
                }
                drawRectangleForCheckStatusBar(boxesArr);
//                handleSingleBoxes(boxesArr);
                // graw all
//                for (int i = 0; i < boxesArr.length; i++) {
//                    int topLeftX = (int)boxesArr[i].x;
//                    int topLeftY = (int)boxesArr[i].y;
//                    int bottomRightX = (int)(boxesArr[i].x + (int)boxesArr[i].w);
//                    int bottomRightY = (int)(boxesArr[i].y + (int)boxesArr[i].h);
//                    System.out.println("obj hahaha: " + boxesArr[i].toString());
//                    View view = drawRectangle(topLeftX, topLeftY, bottomRightX, bottomRightY);
//                    viewList.add(view);
//                }

            }
        }
    };

    public static String uploadImage(Bitmap bitmap, String url) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
        byte[] byteArray = stream.toByteArray();
        RequestBody fileBody = RequestBody.create(byteArray, MediaType.parse("image/jpg"));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "avatar", fileBody)
                .build();
        Request request;
        Call call;
        Response response;
        try {
            request = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "multipart/form-data")
                    .post(requestBody)
                    .build();
            call = new OkHttpClient().newCall(request);
            response = call.execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void saveBitmap(Bitmap bitmap, String bitName) {
        File file = new File("/sdcard/DCIM/test/" + bitName);
        if (file.exists()) {
            file.delete();
        }
        FileOutputStream out;
        try {
            out = new FileOutputStream(file);
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)) {
                out.flush();
                out.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private volatile List<View> viewList = new ArrayList<>();

    public static final Object workerLockObj = new Object();


    public static String getRandomString() {
        int length = 10;
        String str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(62);
            sb.append(str.charAt(number));
        }
        return sb.toString();
    }


    public class Worker extends Thread {
        public ObjGod[] objects = null;
        public volatile long eventTimestamp = 0;
        public volatile boolean done = false;

        @Override
        public void run() {
            while (true) {
                if (stopFlag) {
                    stopFlag = false;
                    removeRectangle(viewList);
                    yolov5ncnn = null;
                    Log.i("worker", "stop working");
                    break;
                }
                if (workerLockObj != null) {
                    synchronized (workerLockObj) {
                        if (workerLockObj != null) {
                            try {
                                Log.i("Waiting", "for event");
                                workerLockObj.wait(1000);
                                long curTimestamp = System.currentTimeMillis();
                                if (curTimestamp - eventTimestamp >= detectTiming && !done) {
                                    Log.i("Working", "running detect");
                                    done = true;
                                    // takeScreenshot module
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        takeScreenshot(Display.DEFAULT_DISPLAY,
                                                getApplicationContext().getMainExecutor(), new TakeScreenshotCallback() {
                                                    @RequiresApi(api = Build.VERSION_CODES.R)
                                                    @Override
                                                    public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                                                        Log.i("from_android_bitmap_resize", "onSuccess " + screenshotResult.toString());
                                                        Bitmap bitmap = Bitmap.wrapHardwareBuffer(screenshotResult.getHardwareBuffer(), screenshotResult.getColorSpace());
                                                        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
//                                                        ObjGod[] objects = yolov5ncnn.Detect(bitmap, false);
                                                        // tmp
//                                                        saveBitmap(bitmap, getRandomString() + ".jpg");
//                                                Random r = new Random();
//                            ObjGod[] objects = {new ObjGod(100.0f, 150.0f, 50.0f, 50.0f, "open", 0.89f)};
//                                                ObjGod[] objects = {new ObjGod(r.nextFloat() * 1000, r.nextFloat() * 1000, r.nextFloat() * 100, r.nextFloat() * 100, "open", 0.89f)};
//                                                        if (objects != null) {
//                                                            Message mes = new Message();
//                                                            mes.what = 1;
//                                                            mes.obj = objects;
//                                                            handler.sendMessage(mes);
//                                                        } else {
//                                                            Log.i("obj null", "nothing detected");
//                                                        }
                                                    }

                                                    @Override
                                                    public void onFailure(int i) {
                                                        Log.i("ScreenShotResult", "onFailure code is " + i);
                                                    }
                                                });
                                    } else {
                                        Log.i("ScreenShotResult", "Build.VERSION.SDK_INT < Build.VERSION_CODES.R ??? ");
                                    }
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
    }

    private ArrayList<AccessibilityNodeInfo> listA = new ArrayList<>();

    public void recycle(AccessibilityNodeInfo info) throws InterruptedException {
        if (info.getChildCount() == 0) {
            listA.add(info);
        } else {
            for (int i = 0; i < info.getChildCount(); i++) {
                if (info.getChild(i) != null) {
                    recycle(info.getChild(i));
                }
            }
        }
    }

    private void getSystemApk()
    {
        PackageManager packageManager = getPackageManager();
        List<PackageInfo> packageList = packageManager.getInstalledPackages(0);
        for (PackageInfo packageInfo : packageList) {
            // 判断是否为系统应用
            if ((packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    systemApks.add(packageInfo.packageName);
        }
        String defaultInputMethod = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if(defaultInputMethod!=null) systemApks.add(defaultInputMethod);
        systemApks.add("com.darpa");
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i("TAG", String.valueOf(jumpFlag));
            if (event.getPackageName() != null ) {
                String packageName = event.getPackageName().toString();
                Log.i("TAG", "lastPackageName:" + lastPackageName + "   package:" + packageName);
                //正常通过桌面或系统应用发生跳转
                if (lastPackageName.equals(packageName) || systemApks.contains(packageName) || systemApks.contains(lastPackageName)) {
                    if (!lastPackageName.equals(packageName) && !packageName.equals("com.darpa")) {
                        lastPackageName = packageName;
                        if (event.getClassName() != null)
                            lastPackageClass = event.getClassName().toString();
                        else lastPackageClass = null;
                    }
                    if(lastPackageName.equals("com.darpa") || packageName.equals("com.darpa"))
                        jumpFlag=false;
                }
                //第三方应用打开第三方应用
                else if(jumpFlag){
                    Log.i("TAG", event.getPackageName().toString() + "发生跳转");
                    // 创建AlertDialog对象
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("您准备跳转到其他应用");
                    builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // 确认按钮的点击事件
                            dialog.dismiss();
                            if (!lastPackageName.equals(packageName) && !packageName.equals("com.darpa") ) {
                                lastPackageName = packageName;
                                if (event.getClassName() != null)
                                    lastPackageClass = event.getClassName().toString();
                                else lastPackageClass = null;
                            }

                        }
                    });
                    builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @SuppressLint("QueryPermissionsNeeded")
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.i("TAG",packageName+"返回app:"+lastPackageName );
                            Intent intent = new Intent();
                            if(lastPackageClass!=null)
                            {
                                intent.setComponent(new ComponentName(lastPackageName, lastPackageClass));
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                try{
                                    jumpFlag = false;
                                    startActivity(intent);
                                    ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                                    activityManager.killBackgroundProcesses(packageName);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    performGlobalAction(GLOBAL_ACTION_HOME);
                                }
                            }
                            else
                            {
                                PackageManager packageManager = getPackageManager();
                                Intent it = packageManager.getLaunchIntentForPackage(lastPackageName);
                                if (it != null){
                                    jumpFlag = false;
                                    startActivity(it);
                                    ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                                    activityManager.killBackgroundProcesses(packageName);
                                }else{
                                    performGlobalAction(GLOBAL_ACTION_HOME);
                                }
                            }
                            dialog.dismiss();
                        }
                    });
                    // 显示提示框
                    AlertDialog alertDialog = builder.create();
                    alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
                    alertDialog.show();
                }
                //返回原来app，避免再次出现弹窗
                else jumpFlag = true;

            }
        }
        
        // During the user operation, the system continuously sends "sendAccessibilityEvent"
        // onAccessibilityEvent can capture this event.
        // If it detects that the time interval between two consecutive events is less than 3000ms,
        // it is considered as consecutive events and will not be processed.
        if (System.currentTimeMillis() - mLastEventTime < 3000) {
            Log.i("ConsecutiveEvent", "Do not process");
            return;
        }
        mLastEventTime = System.currentTimeMillis();

        Log.i("event type", event.toString());
        listA.removeAll(listA);
        Log.i(TAG, "================Widget====================");
        AccessibilityNodeInfo rowNode = getRootInActiveWindow();
        if (rowNode == null) {
            Log.i(TAG, "noteInfo is　null");
            return;
        } else {
            try {
                recycle(rowNode);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.i(TAG, "==============================================");
        removeRectangle(viewList);
        if (workerLockObj != null) {
            synchronized (workerLockObj) {
                if (workerLockObj != null) {

                    worker.eventTimestamp = System.currentTimeMillis();
                    worker.done = false;
                    workerLockObj.notify();
                }
            }
        }
    }

    synchronized private void removeRectangle(List<View> viewList) {
        if (viewList.size() == 0) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        for (View view : viewList) {
            wm.removeView(view);
        }
        viewList.clear();
    }

    private View layoutHelperWnd;

    private void drawRectangleForCheckStatusBar(ObjGod[] boxesArr) {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams topLp = new WindowManager.LayoutParams();

        topLp.type = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY :
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        topLp.gravity = Gravity.RIGHT | Gravity.TOP;
        topLp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        topLp.width = WindowManager.LayoutParams.MATCH_PARENT;
        topLp.height = WindowManager.LayoutParams.MATCH_PARENT;
        topLp.format = PixelFormat.TRANSPARENT;
        layoutHelperWnd = new View(this); //View helperWnd;

        wm.addView(layoutHelperWnd, topLp);

        layoutHelperWnd.post(new Runnable() {
            @Override
            public void run() {
                int[] top1 = new int[2];
                layoutHelperWnd.getLocationOnScreen(top1);
                Log.d("layoutHelperWnd's location1", top1[0] + " " + top1[1]);
                int[] top2 = new int[2];
                layoutHelperWnd.getLocationInWindow(top2);
                Log.d("layoutHelperWnd's location2", top2[0] + " " + top2[1]);
                handleSingleBoxes(wm, boxesArr, top1[1] - top2[1]);
                wm.removeView(layoutHelperWnd);
            }
        });
    }


    @SuppressLint({"UseCompatLoadingForDrawables", "ClickableViewAccessibility"})
    private View drawRectangle(WindowManager wm, int topLeftX, int topLeftY, int bottomRightX, int bottomRightY, String label, int heightOffset) {


        int windowHeight = layoutHelperWnd.getHeight();
        int windowWidth = layoutHelperWnd.getWidth();

        Log.i("drawRectangle windowHeight: ", Integer.toString(windowHeight));
        Log.i("drawRectangle windowWidth: ", Integer.toString(windowWidth));

        int width = wm.getDefaultDisplay().getWidth();
        int height = wm.getDefaultDisplay().getHeight();

        Log.i("drawRectangle topLeftX: ", Integer.toString(topLeftX));
        Log.i("drawRectangle topLeftY: ", Integer.toString(topLeftY));
        Log.i("drawRectangle bottomRightX: ", Integer.toString(bottomRightX));
        Log.i("drawRectangle bottomRightY: ", Integer.toString(bottomRightY));
        Log.i("drawRectangle width: ", Integer.toString(width));
        Log.i("drawRectangle height: ", Integer.toString(height));


        //Setting attributes for Green and Red rectangle layouts
        WindowManager.LayoutParams lpGreen = new WindowManager.LayoutParams();
        lpGreen.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lpGreen.format = PixelFormat.TRANSLUCENT;
        lpGreen.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lpGreen.gravity = Gravity.TOP | Gravity.LEFT;
        lpGreen.width = bottomRightX - topLeftX;
        lpGreen.height = bottomRightY - topLeftY;
        lpGreen.x = topLeftX;
        lpGreen.y = topLeftY - heightOffset;

        WindowManager.LayoutParams lpRed = new WindowManager.LayoutParams();
        lpRed.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lpRed.format = PixelFormat.TRANSLUCENT;
        lpRed.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lpRed.gravity = Gravity.TOP | Gravity.LEFT;
        lpRed.width = bottomRightX - topLeftX;
        lpRed.height = bottomRightY - topLeftY;
        lpRed.x = topLeftX;
        lpRed.y = topLeftY - heightOffset;

        WindowManager.LayoutParams lpRedMC = new WindowManager.LayoutParams();
        lpRedMC.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lpRedMC.format = PixelFormat.TRANSLUCENT;
        lpRedMC.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lpRedMC.gravity = Gravity.TOP | Gravity.LEFT;
        lpRedMC.width = bottomRightX - topLeftX;
        lpRedMC.height = bottomRightY - topLeftY;
        lpRedMC.x = topLeftX;
        lpRedMC.y = topLeftY - heightOffset;

        //Setting attributes for 4 mc rectangle layouts
        WindowManager.LayoutParams mc1 = new WindowManager.LayoutParams();
        mc1.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        mc1.alpha = 0;
        mc1.format = PixelFormat.TRANSLUCENT;
        mc1.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mc1.gravity = Gravity.TOP | Gravity.LEFT;
        mc1.width = Math.min(bottomRightX - topLeftX + 200, windowWidth - (topLeftX > 100 ? topLeftX - 100 : 0));
        mc1.height = Math.min(100, (topLeftY - heightOffset > 100 ? 100 : topLeftY - heightOffset));
        mc1.x = topLeftX > 100 ? topLeftX - 100 : 0;
        mc1.y = topLeftY - heightOffset > 100 ? topLeftY - heightOffset - 100 : 0;

        WindowManager.LayoutParams mc2 = new WindowManager.LayoutParams();
        mc2.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        mc2.alpha = 0;
        mc2.format = PixelFormat.TRANSLUCENT;
        mc2.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mc2.gravity = Gravity.TOP | Gravity.LEFT;
        mc2.width = Math.min(bottomRightX - topLeftX + 200, windowWidth - (topLeftX > 100 ? topLeftX - 100 : 0));
        mc2.height = Math.min(100, windowHeight + heightOffset - bottomRightY);
        mc2.x = topLeftX > 100 ? topLeftX - 100 : 0;
        mc2.y = bottomRightY - heightOffset;

        WindowManager.LayoutParams mc3 = new WindowManager.LayoutParams();
        mc3.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        mc3.alpha = 0;
        mc3.format = PixelFormat.TRANSLUCENT;
        mc3.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mc3.gravity = Gravity.TOP | Gravity.LEFT;
        mc3.width = topLeftX > 100 ? 100 : topLeftX;
        mc3.height = bottomRightY - topLeftY;
        mc3.x = topLeftX > 100 ? topLeftX - 100 : 0;
        mc3.y = topLeftY - heightOffset;

        WindowManager.LayoutParams mc4 = new WindowManager.LayoutParams();
        mc4.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        mc4.alpha = 0;
        mc4.format = PixelFormat.TRANSLUCENT;
        mc4.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mc4.gravity = Gravity.TOP | Gravity.LEFT;
        mc4.width = Math.min(100, windowWidth - bottomRightX);
        mc4.height = bottomRightY - topLeftY;
        mc4.x = bottomRightX;
        mc4.y = topLeftY - heightOffset;

        ImageView imageView = new ImageView(this);
        ImageView imgViewRedMC = new ImageView(this);

        ImageView imgViewMC1 = new ImageView(this);
        ImageView imgViewMC2 = new ImageView(this);
        ImageView imgViewMC3 = new ImageView(this);
        ImageView imgViewMC4 = new ImageView(this);

        // 在 imgViewRedMC 上设置触摸事件监听器
        imgViewRedMC.setOnTouchListener(new View.OnTouchListener() {
            private boolean touchFlag = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 获取触摸事件的位置坐标
                int x = (int) event.getRawX();
                int y = (int) event.getRawY();
                Log.i("Test", "X: " + x + "---");
                Log.i("Test", "Y: " + y + "---");

                // 获取 imgViewRedMC 的位置和尺寸信息
                int[] location = new int[2];
                imgViewRedMC.getLocationOnScreen(location);
                int left = location[0];
                int top = location[1];
                int right = left + imgViewRedMC.getWidth();
                int bottom = top + imgViewRedMC.getHeight();

                // 检测触摸事件是否在 imgViewRedMC 区域内
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (x >= left && x <= right && y >= top && y <= bottom) {
                        // 用户点击了 imgViewRedMC
                        if (!touchFlag) {
                            Toast.makeText(MyAccessibilityService.this, "再按一次以确认点击", Toast.LENGTH_SHORT).show();
                            //移除imgViewRedMC
                            wm.removeView(imgViewRedMC);
                            touchFlag = true;
                            return true;
                        } else {
                            Log.i("进行第二次点击！", TAG);
                            touchFlag = false;
                            return false;
                        }
                    }
                }
                return false;
            }
        });


        if (label.equals(StaticData.LABEL_OPEN)) {
            imageView.setBackground(getResources().getDrawable(R.drawable.red_ractangle));
            wm.addView(imageView, lpRed);
            if (isDefendMode) {
                wm.addView(imgViewRedMC, lpRedMC);
            }
        } else if (label.equals(StaticData.LABEL_CROSS) || label.equals(StaticData.LABEL_SKIP)) {
            imageView.setBackground(getResources().getDrawable(R.drawable.green_ractangle));
            wm.addView(imageView, lpGreen);

            imgViewMC1.setBackground(getResources().getDrawable(R.drawable.mc_rectangle));
            imgViewMC2.setBackground(getResources().getDrawable(R.drawable.mc_rectangle));
            imgViewMC3.setBackground(getResources().getDrawable(R.drawable.mc_rectangle));
            imgViewMC4.setBackground(getResources().getDrawable(R.drawable.mc_rectangle));
            wm.addView(imgViewMC1, mc1);
            wm.addView(imgViewMC2, mc2);
            wm.addView(imgViewMC3, mc3);
            wm.addView(imgViewMC4, mc4);
            viewList.add(imgViewMC1);
            viewList.add(imgViewMC2);
            viewList.add(imgViewMC3);
            viewList.add(imgViewMC4);
        }
        return imageView;
    }


}