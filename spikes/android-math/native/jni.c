#include <jni.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    float *data;
    size_t len;
} CanvasBuffer;

extern CanvasBuffer texlet_canvas_layout(
    const uint8_t *font, size_t font_len,
    const uint8_t *source, size_t source_len, float size, uint8_t display);

extern void texlet_canvas_free(CanvasBuffer buffer);

JNIEXPORT jfloatArray JNICALL
Java_mg_abela_axiora_mathspike_MathCanvas_nativeLayout(
    JNIEnv *env, jclass type, jbyteArray font, jbyteArray source, jfloat size, jboolean display) {
    (void)type;
    const jsize font_len = (*env)->GetArrayLength(env, font);
    const jsize source_len = (*env)->GetArrayLength(env, source);
    jbyte *font_bytes = (*env)->GetByteArrayElements(env, font, NULL);
    if (font_bytes == NULL) {
        return NULL;
    }
    jbyte *source_bytes = (*env)->GetByteArrayElements(env, source, NULL);
    if (source_bytes == NULL) {
        (*env)->ReleaseByteArrayElements(env, font, font_bytes, JNI_ABORT);
        return NULL;
    }
    CanvasBuffer buffer = texlet_canvas_layout(
        (const uint8_t *)font_bytes, (size_t)font_len,
        (const uint8_t *)source_bytes, (size_t)source_len, size, display);
    (*env)->ReleaseByteArrayElements(env, source, source_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, font, font_bytes, JNI_ABORT);

    jfloatArray result = NULL;
    if (buffer.data != NULL && buffer.len <= INT32_MAX) {
        result = (*env)->NewFloatArray(env, (jsize)buffer.len);
        if (result != NULL) {
            (*env)->SetFloatArrayRegion(env, result, 0, (jsize)buffer.len, buffer.data);
        }
    }
    texlet_canvas_free(buffer);
    return result;
}
