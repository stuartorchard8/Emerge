// :engine:audio:openal — OGG clips out of a speaker, and no idea what game is asking.
//
// JVM only, because it is nothing but LWJGL: the Android and web hosts reach their own platforms'
// mixers and share the *interface* a game states, not this implementation.
plugins {
    id("buildsrc.convention.kotlin-jvm")
}

buildDir = file("$rootDir/.build/engine-audio-openal")

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:3.3.3"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-openal")
    implementation("org.lwjgl:lwjgl-stb")
}
