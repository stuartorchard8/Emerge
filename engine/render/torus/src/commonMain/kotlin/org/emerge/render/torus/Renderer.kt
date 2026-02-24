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

    fun getUniformLocation(program: Int, name: String): Int
    fun getAttribLocation(program: Int, name: String): Int

    fun putUniform1i(location: Int, v0: Int)
    fun putUniform1f(location: Int, v0: Float)
    fun putUniform2f(location: Int, v0: Float, v1: Float)
    fun putUniform4fv(location: Int, v: FloatArray, count: Int)

    fun useProgram(program: Int)
    fun drawTriangles(first: Int, count: Int)
}
