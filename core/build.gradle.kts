import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("floodgate.generate-templates")
}

dependencies {
    api(projects.api)
    api("org.geysermc.configutils", "configutils", Versions.configUtilsVersion)

    compileOnly(projects.ap)
    annotationProcessor(projects.ap)

    api("com.google.inject", "guice", Versions.guiceVersion)
    api("com.nukkitx.fastutil", "fastutil-short-object-maps", Versions.fastutilVersion)
    api("com.nukkitx.fastutil", "fastutil-int-object-maps", Versions.fastutilVersion)
    api("com.nukkitx.fastutil", "fastutil-object-short-maps", Versions.fastutilVersion)
    api("org.java-websocket", "Java-WebSocket", Versions.javaWebsocketVersion)
    api("org.incendo", "cloud-core", Versions.cloudCore)
    api("org.bstats", "bstats-base", Versions.bstatsVersion)
    api("org.msgpack", "msgpack", Versions.msgpackVersion)
    api("com.zaxxer", "HikariCP", "4.0.3")
    compileOnly("com.google.code.gson", "gson", "2.8.8")
    testImplementation("com.google.code.gson", "gson", "2.8.8")
}

// present on all platforms
provided("io.netty", "netty-transport", Versions.nettyVersion)
provided("io.netty", "netty-codec", Versions.nettyVersion)

relocate("org.bstats")
relocate("org.msgpack")

tasks {
    templateSources {
        replaceToken("floodgateVersion", fullVersion())
        replaceToken("branch", branchName())
        replaceToken("buildNumber", buildNumber())
    }
    named<Jar>("jar") {
        archiveClassifier.set("")
    }
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("shaded")
    }
}
