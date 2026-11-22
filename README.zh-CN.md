# AirPlay Fit · 安卓电视 AirPlay 投屏接收器

[下载 APK](https://github.com/coderhwz/airplay-fit/releases/latest) · [English](README.md)

AirPlay Fit 是面向 Android TV 和安卓电视盒子的轻量 AirPlay 接收端，支持 iPhone 和 Mac 屏幕镜像、弹幕场景下自动去黑边，以及遥控器画面调整。适合把 iPhone 上的抖音短视频、红果短剧投到电视上看。

## 特色

- **开着弹幕也能自动裁黑边。** 接收端结合多帧画面分别判断上下左右四条边，过滤疑似滚动弹幕的干扰。边界稳定约 3 秒后锁定裁切范围并停止取样，后续弹幕经过时画幅保持稳定。已在 Android 9 电视盒子上用 iPhone 镜像实测。落在裁切区域里的弹幕也会被裁掉。
- **用电视遥控器控制画面。** 底部菜单提供「自动去黑边」「原比例」「等比填满」和「更多」。按一次确定就应用，画面设置会保留到下次投屏。
- **镜像帧率上限可选 30 或 60 fps。** 默认 30 fps，切换后重新连接生效。发送端决定实际帧率，播放时按上键或信息键可以查看接收帧率、码率和解码丢帧。
- **轻量接收端。** 当前 APK 约 295 KB，最低支持 Android 5.1（API 22），无需 root。支持屏幕镜像、音频投放和兼容的 MP4、HLS 视频直投。

## 抖音、红果短剧怎么投屏到电视

让 iPhone 和电视盒子连到同一局域网。在电视上打开 AirPlay Fit，然后在 iPhone「控制中心 → 屏幕镜像」里选择 AirPlay Fit，再打开抖音或红果短剧播放。

- **竖屏短视频**可以选「原比例」保留人物和字幕。竖屏内容在横屏电视上显示时，左右留边属于正常现象。
- **横屏剧集**如果画面四周有黑边，可以选「自动去黑边」。想铺满电视时，可以选「等比填满」，超出屏幕的部分会被裁掉。

具体视频能否镜像取决于 App 和片源的播放限制。播放器内的 AirPlay 直投取决于 App 能否提供兼容的视频流。

## 画面模式

镜像时按遥控器的 **确定** 或 **菜单** 打开画面模式，左右移动，按确定应用。再次选择「自动去黑边」可以重新检测。

| 模式 | 效果 |
| --- | --- |
| 自动去黑边 | 识别并裁去片源里的黑边，稳定后锁定。 |
| 原比例 | 完整保留画面，等比例缩放，直到宽或高先贴合屏幕。 |
| 等比填满 | 等比例放大到铺满屏幕，裁掉超出的部分。 |
| 宽画面裁成 16:9 | 对宽于 16:9 的画面左右对称裁切。 |
| 手动裁边 | 四边分别以 1% 为步长调整，每边最多 40%。 |
| 拉伸全屏 | 让画面贴住四边，宽高比可能改变。 |
| 原始像素（1:1） | 一个视频像素对应一个输出像素，超出屏幕的部分会裁掉。 |
| 固定 4:3、14:9、16:9 | 把画面调整为指定比例后适配屏幕。 |
| 非线性拉伸 | 多拉伸画面两侧，中央尽量保持原比例。 |
| 自定义缩放与位置 | 在原比例适配的基础上缩放至 50%–200%，并独立调整水平和垂直位置。 |
| 旋转 | 每次顺时针转 90°，可和任意模式搭配。 |

手动裁边和缩放使用左右键实时调整，按返回键回到上一层。

## 镜像与视频直投

- **屏幕镜像。** 在 iPhone 或 Mac 的控制中心选择「屏幕镜像」，然后选中 AirPlay Fit。镜像短暂中断后，电视会保留播放界面 8 秒等待重连。
- **视频直投。** 在支持 AirPlay 的播放器里选择 AirPlay Fit，由电视盒子直接播放自己能访问的 HTTP(S) MP4 或 HLS 视频。只要视频地址仍可访问，发送端断开后也可以继续播放。遥控器的确定键控制播放暂停，左右键快退或快进 10 秒，下键显示进度，菜单键打开画面模式。

视频直投取决于发送端提供的地址格式和权限。当前版本支持常见 HTTP(S) 视频地址，兼容范围之外包括 FairPlay DRM 视频和部分 App 私有中继格式。

## 电视盒子适配

各设备使用同一个通用 APK，安装包未内置按 CPU 架构区分的原生库。最低系统版本是 Android 5.1（API 22），镜像表现取决于设备的解码能力。

| 设备 | 系统 | 验证情况 |
| --- | --- | --- |
| Cmiot Box | Android 9，32 位 ARM | 已实测 AirPlay Fit、iPhone 镜像，以及有弹幕时自动去黑边并锁定。 |
| Fire TV 第 2 代（AFTS、MT8173） | Fire OS 5、Android 5.1 | 上游接收端实测 1080p30，AirPlay Fit 待验证。 |
| Chromecast with Google TV 4K（sabrina） | Android 14 | 上游接收端实测 1080p30，Mac 发送可达 4K30，AirPlay Fit 待验证。 |

## 构建与安装

预编译 APK 可从 [GitHub Releases](https://github.com/coderhwz/airplay-fit/releases/latest) 下载。

准备 JDK 17、Android SDK 34 和 ADB。在项目根目录执行以下命令，最后一条适用于 Android 10 及以上，需要接收端从后台打开投屏画面时执行。

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
# Android 10+，允许接收端从后台打开播放界面
adb shell appops set com.hwz.airplayfit SYSTEM_ALERT_WINDOW allow
```

在电视上打开 AirPlay Fit，确保手机或电脑与电视位于同一局域网。开机启动默认关闭，可以在应用首页开启。

## 许可证与致谢

项目沿用 GPL-3.0，基于 Android TV AirPlay Receiver。FairPlay 相关代码来自采用 MIT 许可证的 java-airplay-lib，协议实现参考了 UxPlay。[许可证](LICENSE) · [上游项目](https://github.com/exgota/android-tv-airplay-receiver) · [java-airplay-lib](https://github.com/serezhka/java-airplay-lib) · [UxPlay](https://github.com/FDH2/UxPlay)
