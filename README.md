# APK Extractor (Agent Tools)

给 Agent 用的安装包提取器。传一个包名进去，它把该应用的安装包（含 split APK）
拷贝到系统下载目录，然后弹个 Toast 告诉你在哪。

无界面、无窗口，干完就退。

```
am start -n rxinns.apkextractor.agent/.ExtractActivity --es pkg com.example.app
```

---

## 为什么会有这个

手机上的应用想导出安装包，常规路子有两条：`adb pull`（要连电脑、要开发者模式）
和第三方提取器 App（要装、广告多、还得点来点去）。

这东西是给 Agent 场景设计的——一条 `am start` 指令就能触发，不需要人操作，
输出路径可预测。看到这个仓库的话，直接 `am start` 就行。

---

## 功能

- **传包名即提取**，三种传参方式（见下）
- **支持 split APK**（App Bundle 拆包出来的多个 apk 全部导出）
- **无参数启动会弹输入框**，手动敲包名也行
- **minSdk 1 / targetSdk 34**，老机新机通吃
- **纯 Java 无 native**，一个 apk 覆盖 armeabi-v7a / arm64-v8a / x86 / x86_64
- API ≥ 29 走 MediaStore，**不需要存储权限**

---

## 传入方式

### A. 命令行（三种任选，完全等价）

```bash
# 1) 显式组件 + --es 参数（推荐）
am start -n rxinns.apkextractor.agent/.ExtractActivity --es pkg com.example.app

# 2) data URI（scheme 支持 pkg / package / apkextract）
am start -n rxinns.apkextractor.agent/.ExtractActivity -d "pkg://com.example.app"

# 3) 隐式 action
am start -a rxinns.apkextractor.agent.EXTRACT --es pkg com.example.app
```

extra 的 key 认这四个，任选其一：`pkg` / `package` / `packageName` / `package_name`

### B. 手动输入

**不带任何参数直接点图标启动** → 弹一个对话框：

- 一个输入框（灰色提示"手动提供包名……"）
- 只有一个「确定」按钮，没有取消键

点确定后：

- **框里有字** → 当作包名，立刻提取
- **框里是空的** → 直接退出

---

## 输出

```
/sdcard/Download/<应用名>-<versionName>-<versionCode>.apk
```

举例：`小米换机-4.5.5.4-45540.apk`

多 apk（split APK）会写成 `...-1.apk`、`...-2.apk` 这种后缀。
文件名撞了的话 MediaStore 会自动加 ` (1)`。

---

## 兼容性设计

**minSdk 1 / targetSdk 34。**

| 场景 | 走哪条路 |
|---|---|
| API ≥ 29 | MediaStore.Downloads 插入（反射调用，免存储权限） |
| API < 29 | 直接写 `/sdcard/Download`（需 WRITE_EXTERNAL_STORAGE） |
| API < 19 | 同上，老机权限模型是安装即全给 |

高版本 API 全部走反射（`MediaStore.Downloads`、`getLongVersionCode`），
所以拿 android-23 的 android.jar 就能编译，旧机上也不会 NoClassDefFoundError。

**ABI**：纯 Java，不带任何 native so。dex 本身架构无关，一个 apk 同时支持
armeabi-v7a / arm64-v8a / x86 / x86_64，不需要分包。

**Android 11+ 包可见性**：申请了 `QUERY_ALL_PACKAGES`。不加这个的话，
`getPackageInfo` 对没打过交道的包会抛 NameNotFoundException——
不是"读不了"，是"根本看不见"。Play 商店对该权限有上架限制，侧载自用无所谓。

---

## 构建

```bash
./build.sh
```

产物：`build/extractor_signed.apk`

**依赖**：

- `javac`（OpenJDK 8+）
- `/usr/lib/android-sdk/platforms/android-23/android.jar`
- `com.android.dx.jar`（包名 `dalvik-exchange`）
- `aapt` / `zipalign` / `apksigner` / `keytool`
- `python3` + `Pillow`（画图标用）

Debian/Ubuntu 上大致这么装：

```bash
apt-get install -y openjdk-17-jdk-headless dalvik-exchange \
  aapt apksigner zipalign python3-pil
# android.jar 自己从 SDK 里放一份到 /usr/lib/android-sdk/platforms/android-23/
```

首次构建会自动生成 `build/extractor.keystore`（密码写在 `build.sh` 里）。
**这个文件别丢**，丢了就没法覆盖升级，得先卸载再装。

---

## 安装（真机）

`pm install` 读不了 `/sdcard`（SELinux 拦 system_server 读 fuse 上下文），
必须先落到 `/data/local/tmp`：

```bash
cp /sdcard/Download/extractor_signed.apk /data/local/tmp/
pm install -r -d --user 0 /data/local/tmp/extractor_signed.apk
```

**MIUI 用户注意**：不加 `--user 0` 会报 `INSTALL_FAILED_USER_RESTRICTED`，
即使是用 Shizuku / adb 也一样。加上就过了。

---

## 文件结构

```
apk_extractor/
├── AndroidManifest.xml
├── src/rxinns/apkextractor/agent/ExtractActivity.java   全部逻辑
├── make_icon.py                                          用 Pillow 画 5 个密度的图标
├── res/mipmap-*/ic_launcher.png
├── build.sh
└── build/extractor_signed.apk                            （构建产物，不入库）
```

---

## 踩过的坑

**1. Activity 的 `android:name` 必须写全类路径**

manifest 的 `package=` 是应用包名，但 Activity 的 `android:name` 要写完整类路径
`rxinns.apkextractor.agent.ExtractActivity`（对应 Java 里的
`package rxinns.apkextractor.agent;`）。

写成 `.ExtractActivity` 会被展开成 `rxinns.apkextractor.agent.ExtractActivity`
——等等，这个例子其实就是对的。真正的坑在于：**当 manifest 的 `package`
和 Java 包名不一致时**（早期版本两者不同），简写展开出来就是错的，
运行时直接 ClassNotFoundException。所以老老实实写全路径，别赌。

**2. `android:roundIcon` 是 API 25 才有的属性**

用 android-23 的 aapt 编会报 `No resource identifier found`。
同理 `@android:drawable/ic_menu_save` 这类系统图标在新旧资源表里不一定存在。
结论：图标自己画、塞进 `res/` 里最稳。

**3. MIUI 上 `pm install` 要显式 `--user 0`**

见上面「安装」一节。

**4. 端口没释放会 bind 失败**

反复起本地 http 服务传文件时，`OSError: [Errno 98] Address already in use`。
换个端口就行。

---

## 安全说明

- **`build/extractor.keystore` 已在 `.gitignore` 里**，不会入库。
  仓库里的 `build.sh` 会在首次构建时生成一份新的。
- 如果你 fork 后自己发预编译 apk，注意**不同 keystore 签出来的 apk 不能互相覆盖安装**，
  用户得先卸载旧版。
- 申请 `QUERY_ALL_PACKAGES` 是为了能查到任意包的信息。这个权限在 Play 上架有额外审核，
  本工具面向侧载/自用场景。

---

## 许可

MIT

---

## 作者

Rxinns & Deepseek-v4.1-flash
