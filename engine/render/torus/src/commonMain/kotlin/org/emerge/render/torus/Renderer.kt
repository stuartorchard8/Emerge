package org.emerge.render.torus

expect object Renderer {
    val VERTEX_SHADER: Int
    val FRAGMENT_SHADER: Int
    fun createShader(type: Int) : Int
    fun shaderSource(shader: Int, string: String)
    fun compileShader(type: Int)
    fun getCompileStatus(shader: Int): Int
    fun getShaderInfoLog(shader: Int): String
    fun deleteShader(shader: Int)

    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun getProgramLinkStatus(program: Int): Int
    fun getProgramInfoLog(program: Int): String
    fun deleteProgram(program: Int)
}
