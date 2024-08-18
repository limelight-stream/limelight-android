# 首个实现多点触控 灵敏度调节 (触点移速调节) 的 Moonlight分支. <br>
# The first Moonlight fork to implement multi-touch sensitivity tweak (touch point velocity tweak)
# I'm currently working on my moonlight-iOS fork, with too much deviations from the original version:
https://github.com/Moonlight-the-Fried-Fish/moonlight-ios-NativeMultiTouchPassthrough


# Features of this fork:

允许调整触点在右分屏或分屏的移速， 对调节米家游戏视角转动的灵敏度尤其有用。
This is a fork with some manipulation on native multi-touch pointer coordinaties, allows pointer to move faster or slower on specified enhanced touch zone.
Maybe useful for tweaking view rotation sensitivity in some games.
<br><br>

![image](https://github.com/TrueZhuangJia/moonlight-android-Enhanced-MultiTouch/assets/78474576/3bd8efeb-89ab-477d-b501-22f25cdb8fc6)
![image](https://github.com/TrueZhuangJia/moonlight-android-Enhanced-MultiTouch/assets/78474576/0d58b391-71ef-48be-82f8-6fef1649e2eb)


恢复原版moonlight多指敲击屏幕唤醒本地键盘的方式， 同时允许设置敲击手指数量 <br>
Configurable local keyboard toggle: <br>
![image](https://github.com/TrueZhuangJia/moonlight-android-Enhanced-MultiTouch/assets/78474576/416a2960-f0a7-4245-ac62-d8fb53ec4ca7)
![image](https://github.com/TrueZhuangJia/moonlight-android-Enhanced-MultiTouch/assets/78474576/a0edaf21-a174-448e-832c-da2d171cefea)


还有两个的功能， 使用中你可能未必能感觉到有区别：<br>
And some additional features like flat region to eliminate long press jitter:<br>
![image](https://github.com/TrueZhuangJia/moonlight-android-Enhanced-MultiTouch/assets/78474576/0594b3ef-e381-4efc-bc2b-db8f209db272)
![image](https://github.com/TrueZhuangJia/moonlight-android-Enhanced-MultiTouch/assets/78474576/98534adc-48ad-4433-8d7c-e60b88c13466)


触控与显示同步的话，可能有助理于视角旋转时画面的流畅性。


# Moonlight Android

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## Downloads
* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
