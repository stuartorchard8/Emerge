// Dev tool: interactive showcase for the shared immediate-mode UI toolkit in
// :engine:render:torus (panels/buttons/pickers/steppers). Game-agnostic — apps have
// their own hosts; this window exists to iterate on the toolkit itself.
plugins {
    id("buildsrc.convention.desktop-app")
}

buildDir = file("$rootDir/.build/ui-gallery")

dependencies {
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
}

application {
    mainClass = "org.emerge.render.ui.gallery.UIGalleryKt"
}

tasks.register<JavaExec>("renderUIGallery") {
    group = "application"
    description = "Render one frame of the UI Gallery with the real OpenGL toolkit → build/ui-gallery.png"
    mainClass = "org.emerge.render.ui.gallery.UIGallerySnapshotKt"
    classpath = sourceSets["main"].runtimeClasspath
    args("ui-gallery.png")
    workingDir = rootProject.layout.buildDirectory.get().asFile
}
