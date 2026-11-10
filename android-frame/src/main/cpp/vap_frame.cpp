/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 将独立HardwareBuffer绑定为解码纹理适配的FBO；不解析或合成VAP。
 * 生成来源：Codex AI生成
 */
#include <jni.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <dlfcn.h>
#include <cstdint>
#include <memory>

namespace {
using FromJava = AHardwareBuffer* (*)(JNIEnv*, jobject);
struct Target {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    GLuint framebuffer = 0;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC clientBuffer = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC bindImage = nullptr;
    FromJava fromJava = nullptr;
    void clearImage() {
        if (image != EGL_NO_IMAGE_KHR) destroyImage(display, image);
        image = EGL_NO_IMAGE_KHR;
    }
    ~Target() {
        clearImage();
        if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
        if (texture) glDeleteTextures(1, &texture);
    }
};
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_vapkmp_HardwareFrameTarget_create(JNIEnv* env, jobject) {
    auto target = std::make_unique<Target>();
    target->display = eglGetCurrentDisplay();
    target->createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    target->destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    target->clientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    target->bindImage = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    // 只在API29+调用；动态解析API26符号，保持整个AAR可安装到API23。
    static void* android = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    target->fromJava = android ? reinterpret_cast<FromJava>(dlsym(android, "AHardwareBuffer_fromHardwareBuffer")) : nullptr;
    if (target->display == EGL_NO_DISPLAY || !target->createImage || !target->destroyImage ||
            !target->clientBuffer || !target->bindImage || !target->fromJava) {
        fail(env, "Hardware frame EGL extensions unavailable");
        return 0;
    }
    glGenTextures(1, &target->texture);
    glBindTexture(GL_TEXTURE_2D, target->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &target->framebuffer);
    return reinterpret_cast<jlong>(target.release());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_vapkmp_HardwareFrameTarget_bind(JNIEnv* env, jobject, jlong handle, jobject buffer) {
    auto* target = reinterpret_cast<Target*>(handle);
    target->clearImage();
    AHardwareBuffer* hardware = target->fromJava(env, buffer);
    EGLClientBuffer client = hardware ? target->clientBuffer(hardware) : nullptr;
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    target->image = client ? target->createImage(target->display, EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID, client, attributes) : EGL_NO_IMAGE_KHR;
    if (target->image == EGL_NO_IMAGE_KHR) {
        fail(env, "Cannot import hardware frame target");
        return;
    }
    glBindTexture(GL_TEXTURE_2D, target->texture);
    target->bindImage(GL_TEXTURE_2D, target->image);
    glBindFramebuffer(GL_FRAMEBUFFER, target->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target->texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        fail(env, "Incomplete hardware frame framebuffer");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_vapkmp_HardwareFrameTarget_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Target*>(handle);
}
