package rxinns.apkextractor.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 用的安装包提取器。
 *
 * 传入方式（任一）：
 *   1. am start -n rxinns.apkextractor.agent/.ExtractActivity --es pkg com.example.app
 *   2. am start -n rxinns.apkextractor.agent/.ExtractActivity -d "pkg://com.example.app"
 *   3. am start -a rxinns.apkextractor.agent.EXTRACT --es pkg com.example.app
 *   4. 直接点图标 -> 无参数 -> 弹提示，什么都不干
 *
 * 输出：/sdcard/Download/<应用名>-<versionName>-<versionCode>.apk
 *
 * 兼容性：
 *   - 纯 Java，无 native so，dex 架构无关，32/64 位通吃
 *   - minSdk 1 编译，所有高版本 API 走反射，旧机上不会 NoClassDefFoundError
 *   - API>=29 用 MediaStore.Downloads 写公共下载目录（免权限）
 *   - API 19..28 直接写 /sdcard/Download（需要存储权限，安装时给）
 *   - API <19 直接写，权限模型是安装即全给
 */
public class ExtractActivity extends Activity {

    private static final String TAG = "ApkExtractor";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String pkg = resolvePackageName(getIntent());
        Log.i(TAG, "onCreate, pkg=" + pkg + ", sdk=" + Build.VERSION.SDK_INT);
        if (pkg == null || pkg.length() == 0) {
            // 没带参数：弹输入框，让用户自己填包名
            showInputDialog();
            return;
        }

        new Thread(new Runnable() {
            @Override public void run() { doExtract(pkg); }
        }).start();
    }

    /**
     * 无参数启动时弹的对话框。
     * 输入框有内容 -> 提取该包；留空 -> 直接退出。
     */
    private void showInputDialog() {
        final EditText input = new EditText(this);
        input.setHint("手动提供包名……");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        input.setMinWidth((int) (getResources().getDisplayMetrics().density * 200));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Agent用安装包提取器");
        b.setMessage("这是给 Agent 跑命令行提取安装包用的。"
                + "直接点击无效，需要传入包名作参数启动。\n\n"
                + "也可以在这里手动输入包名，点确定就会提取到下载目录。");
        b.setView(box);
        b.setCancelable(false);

        b.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                String v = input.getText() == null
                        ? "" : input.getText().toString().trim();
                if (v.length() == 0) {
                    Log.i(TAG, "手动输入为空，直接退出");
                    finish();
                    return;
                }
                Log.i(TAG, "手动输入包名: " + v);
                final String target = v;
                new Thread(new Runnable() {
                    @Override public void run() { doExtract(target); }
                }).start();
            }
        });
        // 不用 setNegativeButton，就一个确定键
        b.show();
    }

    /** 从各种入口里把包名抠出来 */
    private String resolvePackageName(Intent it) {
        if (it == null) return null;

        // 1) --es pkg / --es package / --es packageName / --es package_name
        String[] keys = { "pkg", "package", "packageName", "package_name" };
        for (int i = 0; i < keys.length; i++) {
            String v = it.getStringExtra(keys[i]);
            if (v != null && v.length() > 0) return v.trim();
        }

        // 2) data URI: pkg://com.xxx / package://com.xxx / apkextract://com.xxx
        Uri data = it.getData();
        if (data != null) {
            String ssp = data.getSchemeSpecificPart();
            if (ssp != null && ssp.length() > 0) {
                while (ssp.startsWith("/")) ssp = ssp.substring(1);
                if (ssp.length() > 0) return ssp.trim();
            }
            String host = data.getHost();
            if (host != null && host.length() > 0) return host.trim();
        }

        // 3) 有些调用方爱把包名塞进 -d 的 fragment
        if (data != null && data.getFragment() != null
                && data.getFragment().length() > 0) {
            return data.getFragment().trim();
        }
        return null;
    }

    private void doExtract(String pkg) {
        Log.i(TAG, "doExtract start: " + pkg);
        PackageManager pm = getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(pkg, 0);
        } catch (Exception e) {
            Log.e(TAG, "getPackageInfo fail", e);
            toast("找不到这个包：" + pkg);
            finishSoon(3000);
            return;
        }

        ApplicationInfo ai = pi.applicationInfo;
        if (ai == null || ai.sourceDir == null) {
            toast("拿不到安装包路径：" + pkg);
            finishSoon(3000);
            return;
        }

        // 应用显示名，读不到就退回包名
        String label;
        try {
            CharSequence cs = ai.loadLabel(pm);
            label = (cs == null ? pkg : cs.toString());
        } catch (Throwable t) {
            label = pkg;
        }
        label = sanitize(label);

        String verName = (pi.versionName == null ? "unknown" : pi.versionName);
        String verCode = String.valueOf(readVersionCode(pi));
        String base = label + "-" + verName + "-" + verCode;

        // 收集所有 apk：base + split
        List<String> paths = new ArrayList<String>();
        if (ai.splitSourceDirs != null) {
            for (int i = 0; i < ai.splitSourceDirs.length; i++) {
                String s = ai.splitSourceDirs[i];
                if (s != null) paths.add(s);
            }
        }
        if (!paths.contains(ai.sourceDir)) paths.add(0, ai.sourceDir);

        List<String> saved = new ArrayList<String>();
        for (int i = 0; i < paths.size(); i++) {
            String src = paths.get(i);
            String ext = guessExt(src);
            String name = (paths.size() == 1)
                    ? base + ext
                    : base + "-" + (i + 1) + ext;
            Log.i(TAG, "copy #" + i + " src=" + src + " -> " + name);
            String out = copyToDownload(new File(src), name, ext);
            Log.i(TAG, "copy #" + i + " result=" + out);
            if (out != null) saved.add(out);
        }

        if (saved.isEmpty()) {
            toast("提取失败，读不了安装包：" + pkg);
        } else {
            StringBuilder sb = new StringBuilder("已提取到");
            for (int i = 0; i < saved.size(); i++) {
                sb.append("\n").append(saved.get(i));
            }
            toast(sb.toString());
        }
        finishSoon(4500);
    }

    /** versionCode：API 28+ 是 long，之前是 int；getLongVersionCode 旧机上没有，只能反射 */
    private long readVersionCode(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Method m = PackageInfo.class.getMethod("getLongVersionCode");
                Object r = m.invoke(pi);
                if (r instanceof Long) return ((Long) r).longValue();
            } catch (Throwable ignored) { }
        }
        return pi.versionCode;
    }

    /** 把单个文件写到公共下载目录，返回可达路径（失败 null） */
    private String copyToDownload(File src, String displayName, String ext) {
        if (Build.VERSION.SDK_INT >= 29) {
            String r = writeViaMediaStore(src, displayName, ext);
            if (r != null) return r;
            // 万一 MediaStore 那边出事，退回直写
        }
        return writeDirect(src, displayName);
    }

    /** API 29+ 正路：MediaStore 插 Downloads，全反射，旧机不会炸 */
    private String writeViaMediaStore(File src, String displayName, String ext) {
        Uri collection = null;
        try {
            // MediaStore.Downloads.EXTERNAL_CONTENT_URI，用字符串拼出来避免引用新字段
            Class<?> downloads = Class.forName("android.provider.MediaStore$Downloads");
            Object uriObj = downloads.getField("EXTERNAL_CONTENT_URI").get(null);
            collection = (Uri) uriObj;

            ContentResolver cr = getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put("_display_name", displayName);
            cv.put("mime_type", mimeOf(ext));
            cv.put("is_pending", Integer.valueOf(1));

            Uri item = cr.insert(collection, cv);
            if (item == null) return null;

            OutputStream os = cr.openOutputStream(item);
            if (os == null) { cr.delete(item, null, null); return null; }
            pipe(src, os);

            ContentValues done = new ContentValues();
            done.put("is_pending", Integer.valueOf(0));
            cr.update(item, done, null, null);
            Log.i(TAG, "MediaStore ok: " + displayName);
            return "/sdcard/Download/" + displayName;

        } catch (Throwable t) {
            Log.e(TAG, "MediaStore fail: " + displayName, t);
            return null;
        }
    }

    /** 老路：直接写文件系统 */
    @SuppressWarnings("deprecation")
    private String writeDirect(File src, String displayName) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download");
            if (!dir.exists()) dir.mkdirs();
            File dst = new File(dir, displayName);
            FileOutputStream fos = new FileOutputStream(dst);
            pipe(src, fos);
            Log.i(TAG, "direct write ok: " + dst.getAbsolutePath());
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "direct write fail: " + displayName, t);
            return null;
        }
    }

    private void pipe(File src, OutputStream os) throws Exception {
        FileInputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            try { in.close(); } catch (Exception ignored) { }
            try { os.close(); } catch (Exception ignored) { }
        }
    }

    private String guessExt(String path) {
        int i = path.lastIndexOf('.');
        if (i >= 0 && i > path.lastIndexOf('/')) return path.substring(i);
        return ".apk";
    }

    private String mimeOf(String ext) {
        if (".apk".equalsIgnoreCase(ext)) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    /** 文件名里去掉会出事的东西 */
    private String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c == '\n') {
                b.append('_');
            } else {
                b.append(c);
            }
        }
        String r = b.toString().trim();
        return r.length() == 0 ? "app" : r;
    }

    private void toast(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                Toast.makeText(ExtractActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finishSoon(final long delay) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, delay);
    }
}
