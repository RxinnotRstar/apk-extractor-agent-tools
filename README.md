# APK Extractor (Agent Tools)

给 Agent 用的安装包提取器。传一个包名进去，它把该应用的安装包（含 split APK）
拷贝到系统下载目录，然后弹个 Toast 告诉你在哪。

无界面、无窗口，干完就退。

```
am start -n rxinns.apk.extractor.foragent/.ExtractActivity --es pkg com.example.app
```

包名：`rxinns.apk.extractor.foragent`

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
- **API ≥ 29 走 MediaStore**，**不需要存储权限**

---

## 传入方式

### A. 命令行（三种任选，完全等价）

```bash
# 1) 显式组件 + --es 参数（推荐）
am start -n rxinns.apk.extractor.foragent/.ExtractActivity --es pkg com.example.app

# 2) data URI（scheme 支持 pkg / package / apkextract）
am start -n rxinns.apk.extractor.foragent/.ExtractActivity -d "pkg://com.example.app"

# 3) 隐式 action
am start -a rxinns.apk.extractor.foragent.EXTRACT --es pkg com.example.app
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

多 apk（split APK）会写成 `...-1.apk`、`...-2.apk` 这种后缀。
文件名撞了的话 MediaStore 会自动加 ` (1)`。

---

## 兼容性设计

**minSdk 1 / targetSdk 34。**

| 场景 | 走哪条路 |
|---|---|
| API ≥ 29 | MediaStore.Downloads 插入（反射调用，免存储权限） |
| API 19..28 | 直接写 `/sdcard/Download`（需 WRITE_EXTERNAL_STORAGE） |
| API < 19 | 同上，老机权限模型是安装即全给 |

高版本 API 全部走反射（`MediaStore.Downloads`、`getLongVersionCode`、
`splitSourceDirs`），所以拿 android-23 的 android.jar 就能编译，
旧机上也不会 NoClassDefFoundError / NoSuchFieldError。

**ABI**：纯 Java，不带任何 native so。dex 本身架构无关，一个 apk 同时支持
armeabi-v7a / arm64-v8a / x86 / x86_64，不需要分包。

**Android 11+ 包可见性**：申请了 `QUERY_ALL_PACKAGES`。不加这个的话，
`getPackageInfo` 对没打过交道的包会抛 NameNotFoundException——不是"读不了"，
是"根本看不见"。Play 商店对该权限有上架限制，侧载自用无所谓。

### 关于 Android 0.9（结论：不做针对性适配）

0.9 是 2007 年 11 月的预览版（milestone-3 SDK，如 `m3-rc37a`），
**从没随任何零售机型出货**，没有可用于验证的镜像，也基本没有公开 API 文档。
逐条对下来，这个包已经贴着兼容性地板了：

- `minSdkVersion=1`（最低可能值）
- 纯 Java，无 native so，dex 035，不依赖任何 ABI
- 代码里**不引用 R 类**，资源只在 manifest 里被引用
- API 28+/29+ 的能力（`getLongVersionCode`、`MediaStore.Downloads`）全走反射
- 资源只在 manifest 里引用，不依赖 `aapt` 生成的 R
- 签名含 **v1 (JAR) 方案**，且 `minSdkVersion < 18` 时 apksigner 自动退到 SHA-1，
  这正是老系统唯一认识的方案（v2/v3 分别要 Android 7.0 / 9）

**一处真正会炸的地方（已修）**：

```java
if (ai.splitSourceDirs != null)   // 旧写法：API 21 才有的字段，无条件直接访问
```

`ApplicationInfo.splitSourceDirs` 是 API 21（Android 5.0）加入的。
直接访问字段在老系统上执行到这条指令就抛 `NoSuchFieldError`，
而它是 `Error` 不是 `Exception`，跑在子线程里没人接 → 进程直接崩。
现在改成 `getField("splitSourceDirs")` 反射 + `catch (Throwable)`，
取不到就按"没有 split"处理。修完这个，Android 5.0 以下不再是闪退。

**诚实的保留意见**：即使签名和 API 层面都对得上，Android 0.9 的安装器
与现代 APK 的元数据（aapt 写入的 `platformBuildVersionCode` 等）能否兼容，
无法在没有真机/镜像的条件下验证。所以结论是"没有针对性适配的必要，
也不可能验证"，而不是"保证能在 0.9 上跑"。

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
- `aapt` / `zipalign` / `apksigner`
- `python3` + `Pillow`（画图标用）

Debian/Ubuntu 上大致这么装：

```bash
apt-get install -y openjdk-17-jdk-headless dalvik-exchange \
  aapt apksigner zipalign python3-pil
# android.jar 自己从 SDK 里放一份到 /usr/lib/android-sdk/platforms/android-23/
```

### 签名密钥

签名用工作区里的**发布私钥**，不在本仓库内（`.gitignore` 已挡住 `*.keystore`）：

```
KEYDIR = /workspace/安卓安装包私钥
KS     = $KEYDIR/rxinns-release.keystore
ALIAS  = release
密码    = $KEYDIR/.kspass
```

都可以用环境变量覆盖：`KEYDIR=... KS=... ALIAS=... KSPASS=... ./build.sh`

当前证书：

```
DN:     CN=Rxinns, O=Rx1nnS, C=CN
SHA1:   52:48:9C:83:55:69:CB:FD:BC:4F:84:6E:0F:E7:7C:67:3A:4A:81:E5
SHA256: B4:05:A1:2D:3B:D5:4B:D6:7E:74:6B:CF:EB:68:3C:FE:E8:DB:90:2A:5F:6C:74:E3:86:D2:05:FA:B8:AC:E9:89
```

（旧的那把 `build/extractor.keystore`，CN=extractor，已于 2026-09-17 删除，
不再参与构建。注意：手机上那个旧包 `rxinns.apkextractor.agent` 是用它签的，
钥匙没了就再也没法给那个包发更新了，只能卸载重装。）

---

## 安装（真机）

`pm install` 读不了 `/sdcard`（SELinux 拦 system_server 读 fuse 上下文），
必须先落到 `/data/local/tmp`：

```bash
cp /sdcard/Download/extractor_signed.apk /data/local/tmp/
pm install -r -d --user 0 /data/local/tmp/extractor_signed.apk
```

**MIUI 用户注意**：这机器上实测 `INSTALL_FAILED_USER_RESTRICTED`（
`Install canceled by user`）**偶尔会在第一次安装时出现**，同一份 apk
重试一次就 Success，多次试验都复现这个规律。真被卡住的话改用
`--user 0`，或者重试。

---

## 更新仓库（网页上传路线）

有些环境配不通 git 凭据（token / SSH 全认证失败），但网页登录态是好的。
那就绕一圈，用仓库里的 `.github/workflows/unpack.yml`：

1. 把要同步的文件打成一个 `payload.zip`
   —— 条目相对仓库根目录，**不要在压缩包里再套一层文件夹**
2. 网页上把这个 zip 传到仓库根目录 → 触发 Action
   → 自动解压到根目录、删掉 zip、提交

因为 `unzip -o` **只覆盖不删除**，目录改名/迁移之后旧文件会残留。
要清残留，就在包里多放一个 `.unpack-delete`，每行一个待删路径
（`#` 开头当注释，空行忽略），Action 会删掉它们并移除这份清单：

```
# 包名重构：旧路径残留
src/rxinns/apkextractor
```

⚠️ `.unpack-delete` 只在 workflow 是改进版时才生效。

### 关键限制：payload 里绝对不能带 workflow 文件

打 zip 时必须排除 `.github/workflows/*`：

```bash
zip -r payload.zip . -x '.github/workflows/*' -x '.git/*' -x 'build/*' ...
```

原因（实测，run 35225676629）：Action 用的是 `GITHUB_TOKEN`，
它的 `contents: write` 只够推普通文件；GitHub 不允许 App 令牌创建或修改
workflow 文件，于是整个 push 被拒：

```
! [remote rejected] main -> main (refusing to allow a GitHub App to
  create or update workflow `.github/workflows/unpack.yml`
  without `workflows` permission)
```

后果是**整次同步全废**，不是只跳过那一个文件：解压和 commit
都在 runner 里做完了，但推不出去，而 runner 是一次性的，
成果直接蒸发 —— 仓库看起来毫无变化，只有 Actions 页面能看到一次 failed。

所以：payload 只装普通文件。要改 workflow 本身，得由**用户账号**提交
（在网页上进 `.github/workflows/` 目录再上传 `unpack.yml`），
或者给 Action 配一个带 `workflow` 权限的 PAT secret。

---

## 文件结构

```
apk_extractor/
├── AndroidManifest.xml
├── src/rxinns/apk/extractor/foragent/ExtractActivity.java   全部逻辑
├── make_icon.py                                             画图标（外圈牛皮纸色）
├── res/mipmap-*/ic_launcher.png
├── preview/                                                 图标对比预览（不入库）
├── build.sh
└── build/extractor_signed.apk                               （构建产物，不入库）
```

### 图标

`make_icon.py` 从外到内三层：牛皮纸色圆环（`#C7A16B`）→ 深蓝渐变圆角底 →
白色下箭头 + 托板 + 右上角 apk 小方块。

外圈有两种画法，跑 `python3 make_icon.py --preview` 看对比图：

| 参数 | 效果 |
|---|---|
| `flat` | 纯色环，内缘压一道暗线（当前构建用这个） |
| `corrugated` | 环带里加竖向瓦楞暗纹 |
| `none` | 不画外圈，回到老样子 |

---

## 踩过的坑

**1. 包名里不能出现 Java 保留字**

最初想要的包名是 `rxinns.rxinns.apk.extractor.for.agent`，里面的 `for` 是
Java 保留字。实测结论：

- `AndroidManifest.xml` 的 `package` 属性 → **aapt / aapt2 都接受**，
  系统侧完全合法（`for` 在 Android 包名规则里只是普通标识符）
- **Java 源码开头的 `package` 声明 → javac 直接拒绝**：
  `error: <identifier> expected`
- 大写变体 `For` 可以编译，但 `for`/`For` 混用很坑

最后定名 **`rxinns.apk.extractor.foragent`**，彻底绕开保留字。

**2. Activity 的 `android:name` 必须写全类路径**

manifest 的 `package=` 是应用包名，Activity 的 `android:name` 要写完整类路径
`rxinns.apk.extractor.foragent.ExtractActivity`。当 manifest 的 `package`
和 Java 包名不一致时（早期版本两者不同），简写展开出来就是错的，
运行时直接 ClassNotFoundException。所以老老实实写全路径，别赌。

**3. `android:roundIcon` 是 API 25 才有的属性**

用 android-23 的 aapt 编会报 `No resource identifier found`。
同理 `@android:drawable/ic_menu_save` 这类系统图标在新旧资源表里不一定存在。
结论：图标自己画、塞进 `res/` 里最稳。

**4. `splitSourceDirs` 直接访问会在 Android 5.0 以下闪退**

见上面「关于 Android 0.9」一节，字段是 API 21 加的，必须反射。

**5. 端口没释放会 bind 失败**

反复起本地 http 服务传文件时，`OSError: [Errno 98] Address already in use`。
换个端口就行。

---

## 安全说明

- keystore 不在本仓库内，`.gitignore` 里有 `*.keystore` / `*.jks` / `*.p12`。
- **keystore 丢了 = 再也无法给同一个包名发更新**，用户只能卸载重装。
  至少存两份，其中一份离线。
- 申请 `QUERY_ALL_PACKAGES` 是为了能查到任意包的信息。这个权限在 Play 上架有额外审核，
  本工具面向侧载/自用场景。

---

## 许可

MIT

---

## 作者

Rxinns & Deepseek-v4.1-flash
