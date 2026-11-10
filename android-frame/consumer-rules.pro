# JNI按固定类名和方法名查找；保留桥接，不保留任何播放器View。
-keep class io.github.vapkmp.HardwareFrameTarget {
    native <methods>;
}
