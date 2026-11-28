package com.chengyan.app.shell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.view.Surface;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 后台虚拟屏 helper（以 shell uid 经 Shizuku/ADB app_process 运行）。
 * 用法：app_process -Djava.class.path=&lt;apk&gt; /system/bin com.chengyan.app.shell.VdHelper <cmd> [args]
 *   create <w> <h> <dpi>  → 打印 displayId（标记写 /data/local/tmp/chengyan_vscreen）
 *   destroy               → 清标记（display 在 shell 进程退出时由系统回收）
 */
public final class VdHelper {

    private static final String PROP_FILE = "/data/local/tmp/chengyan_vscreen";

    private VdHelper() {}

    public static void main(String[] args) {
        try {
            String cmd = args.length > 0 ? args[0] : "";
            if ("create".equals(cmd)) {
                create(args);
            } else if ("destroy".equals(cmd)) {
                destroy();
            } else {
                System.out.println("ERR usage: VdHelper create <w> <h> <dpi> | destroy");
            }
        } catch (Throwable t) {
            System.out.println("ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        System.exit(0);
    }

    private static Context context() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object at = atClass.getMethod("systemMain").invoke(null);
        return (Context) at.getClass().getMethod("getSystemContext").invoke(at);
    }

    private static void create(String[] args) throws Exception {
        int w = args.length > 1 ? parseInt(args[1], 720) : 720;
        int h = args.length > 2 ? parseInt(args[2], 1600) : 1600;
        int dpi = args.length > 3 ? parseInt(args[3], 320) : 320;
        Context ctx = context();
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);

        SurfaceTexture texture = new SurfaceTexture(false);
        texture.setDefaultBufferSize(w, h);
        Surface surface = new Surface(texture);

        VirtualDisplay vd = null;
        try {
            // 隐藏 API：trusted 公共 display（shell uid 可建，am start --display 可用）
            Method m = DisplayManager.class.getMethod("createVirtualDisplay",
                    String.class, int.class, int.class, int.class, Surface.class,
                    int.class, VirtualDisplay.Callback.class, Handler.class, String.class);
            m.setAccessible(true);
            vd = (VirtualDisplay) m.invoke(dm, "chengyan_vscreen", w, h, dpi, surface,
                    1 | 8 | 16 /* PUBLIC|OWN_CONTENT_ONLY|TRUSTED */, null, null, ctx.getPackageName());
        } catch (Throwable ignore) {
            // 退化：SDK 公开 API
            try {
                vd = dm.createVirtualDisplay("chengyan_vscreen", w, h, dpi, surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
            } catch (Throwable ignore2) { }
        }
        if (vd == null) {
            System.out.println("ERR 创建虚拟 display 被拒绝");
            System.exit(1);
        }
        int id = vd.getDisplay().getDisplayId();
        File f = new File(PROP_FILE);
        java.io.FileWriter fw = new java.io.FileWriter(f);
        fw.write(id + " " + w + " " + h + " " + dpi + " " + System.currentTimeMillis());
        fw.close();
        System.out.println("OK " + id);
    }

    private static void destroy() {
        new File(PROP_FILE).delete();
        System.out.println("OK destroyed");
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
