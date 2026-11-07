import org.gradle.api.attributes.java.TargetJvmVersion

// 核心：事件、模块、设置、网易云接口、播放器、一起听歌。不碰任何绘图。
dependencies {
    "implementation"("org.jcraft:jorbis:0.0.17")     // Ogg Vorbis 解码
    "implementation"("javazoom:jlayer:1.0.1")       // MP3 解码
    "implementation"("com.google.zxing:core:3.3.3") // 登录二维码
    // YouTube 音源：只参与编译，真正打进 jar 的是平台构建（modern26/）里的 shade
    "compileOnly"("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    "compileOnly"("io.github.jaredmdobson:concentus:1.0.2")
    "compileOnly"("de.sfuhrm:jaad:0.8.7")
}

// NewPipeExtractor 需要 JVM 11+，而 core 输出的是 Java 8 字节码。
// 只放宽"编译 classpath"的版本要求（compileOnly，不进 core 的产物），不影响 jar 的目标版本。
configurations.matching { it.name == "compileClasspath" }.configureEach {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
}
