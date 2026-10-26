# 侧滑停顿 SidebarPause

给 OPPO 自带的智能侧边栏换成小米那种触发方式：从屏幕边缘往里滑、**停一下**，侧边栏就呼出来；直接滑走仍然是返回，不影响原来的手势。

## 为什么要做这个

ColorOS 17 的智能侧边栏只认屏幕上那个浮标——你得先点中它才能把面板拉出来。侧边栏 App 自己有个「屏幕两侧呼出」的开关，在 17.9.8 这个版本里是写死关闭的，设置页里根本没有入口。

我不想换掉厂商的面板，只想换掉呼出的方式，所以做成一个 LSPosed 模块。

## 适用设备

OPPO PMA110 / ColorOS 17 / Android 17。**只在这一台机上验证过。** 模块靠类名和方法名去 hook 系统组件，别的机型或系统版本上这些名字可能不一样，装上没反应请先怀疑这一点。

前提：已经 root，并且装了 LSPosed（我用 KernelSU + LSPosed）。

## 它做了什么

- 停顿触发呼出，时长 150~600ms 可调，默认 200ms。滑到边缘内滑一段后停住不动，到点就出面板。
- 左右两侧可以分别开关，从哪边滑就从哪边出，不用先去设置里换边。
- 面板从你手指停住的位置长出来，收回时也回到那个位置，然后 300ms 慢慢淡掉，不会在屏幕边上留一条灰的。
- 隐藏 OPPO 那个全局浮标（默认开着）。不隐藏的话，落在浮标上的手势仍然交给侧边栏自己处理。
- 一个「重启作用域」按钮，点一下同时重启 SystemUI 和侧边栏进程，改完设置或换版本后不用重启整机（需要 root）。

## 怎么装

1. 装 APK。最新正式版在 [Releases](../../releases) 里，也可以直接下
   [SidebarPause-1.17-a910ebc.apk](https://github.com/linjianwuovo/SidebarPause/raw/builds/SidebarPause-1.17-a910ebc.apk)
   （md5 `9293005930698278d66274d31b3a9553`）。
2. 打开 LSPosed，启用本模块，作用域**同时勾选「系统界面」和「OPPO 侧边栏」**。只勾一个不行：浮标和面板的残留是侧边栏进程管的，手势判定是系统界面管的。
3. 打开 App，点「重启作用域（root）」；或者重启手机。
4. App 首页会显示「当前加载：系统界面 ✓ / 侧边栏 ✓」，两个都是 ✓ 才算生效。

然后从屏幕边缘内滑、停一下试试。

## 大概是怎么实现的

**系统界面（com.android.systemui）里**：hook `SideGestureDetector.onMotionEventImpl`，自己跟一遍 DOWN/MOVE 的轨迹，判断"内滑一段之后停住"。命中之后调 `cancelGesture` 把这次返回手势吞掉，让系统别真的返回，然后跳过这轮剩下的事件。

**呼出面板**：`bindService` 连到侧边栏的 `oplus.intent.action.EDGE_PANEL_UI_SERVICE`，用 AIDL 的 `toShowSideBar(Bundle)` 告诉它开面板。Bundle 里的 `showRect` 语义很关键——它不只是"从哪儿弹"，它同时是**面板形变的起点和终点**（`PanelMainView.updateFloatBarRect` 把它写进 `sourceRect`，面板收起时会缩回这个矩形并留在屏幕上）。所以传手指位置的矩形能拿到跟手弹出的效果，代价是收完会留一条灰的；模块在收起动画开跑时把面板 alpha 淡到 0 来解决这个。

**侧边栏（com.coloros.smartsidebar）里**：hook `FloatBarMainView` / `FloatBarView` 和真正 `addView` 的那层 `WindowUtil`，把浮标的可见性和窗口 alpha 压成 0；hook `PanelMainView.changeState` 盯面板的开关状态，处理收起后的残留。

两个进程之间不传数据，只通过 `Settings.Secure` 里的时间戳配合（谁最后呼出过、面板什么时候收的），避免跨进程回调时序问题。

## 碰到系统设置的哪些键

读：`sidebar_scene_region`（侧边栏场景区域）、`key_float_bar_left_right`（浮标在哪一侧）、`edge_panel_os_float_bar_alpha`（浮标透明度）。

写：`key_float_bar_left_right` 和 `edge_panel_os_float_bar_alpha`（换边和隐藏浮标时），以及四个自己的标记 `sidebarpause_last_show_at`、`sidebarpause_panel_closed_at`、`sidebarpause_systemui_loaded`、`sidebarpause_sidebar_loaded`。

全在 `Settings.Secure` 里，都在本机。模块不联网，没有任何数据统计或上传。

## 已知限制

- 只在 PMA110 / ColorOS 17 上跑过，换机型不保证能用。
- 隐藏浮标之后，那块触摸区域可能还在（看不见但能碰到）。
- 面板的开关状态是靠时间戳加状态回调推断的，极端时序下判断可能慢一拍。
- 换系统版本后如果失效，多半是被 hook 的类名/方法名变了，不是开关没打开。

## 自己编译

JDK 17 以上（我用 21），Android SDK 里 compileSdk 37。Xposed API 82 的 `api-82.jar` 已经放在 `app/libs/` 里，`compileOnly` 引用，不需要额外配依赖。

```
gradle assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。release 用的是 debug 签名——这个项目是自用和小范围分享，没有做正式发布签名。

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。

一点说明：`app/libs/api-82.jar` 是 Xposed 框架的编译期桩（stub），只用来让代码能引用
`de.robv.android.xposed.*` 这些类，是 `compileOnly` 依赖，**不会打进 APK**。构建产物里
定义的类全是本项目的，Xposed API 的实现由设备上的框架在运行时提供（已核对过 release 包的 dex
类定义表，里面 1128 个类全是本项目的，没有任何 `de.robv.android.xposed` 类被打包进去）。这个 jar 归 Xposed 上游，
它自己的条款只对它本身有效；你也可以从上游拿同版本的 api jar 替换掉它再编译。

OPPO 的系统组件（SystemUI、智能侧边栏）不属于本项目，本仓库不含它们的任何源码。
