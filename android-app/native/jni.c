#include <jni.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint8_t *data;
    size_t len;
    size_t capacity;
} AxioraBuffer;

extern AxioraBuffer axiora_package_inspect_json(const char *path);
extern AxioraBuffer axiora_package_load_to_directory_json(const char *path, const char *staging);
extern void axiora_package_free_buffer(AxioraBuffer buffer);

static jbyteArray copy_result(JNIEnv *env, AxioraBuffer buffer) {
    jbyteArray result = NULL;
    if (buffer.data != NULL && buffer.len <= INT32_MAX) {
        result = (*env)->NewByteArray(env, (jsize)buffer.len);
        if (result != NULL) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)buffer.len, (const jbyte *)buffer.data);
        }
    }
    axiora_package_free_buffer(buffer);
    return result;
}

static jbyteArray invoke(JNIEnv *env, jstring path, AxioraBuffer (*operation)(const char *)) {
    if (path == NULL) {
        return NULL;
    }
    const char *utf8_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (utf8_path == NULL) {
        return NULL;
    }
    AxioraBuffer buffer = operation(utf8_path);
    (*env)->ReleaseStringUTFChars(env, path, utf8_path);
    return copy_result(env, buffer);
}

static jbyteArray invoke_load(JNIEnv *env, jstring path, jstring staging) {
    if (path == NULL || staging == NULL) {
        return NULL;
    }
    const char *utf8_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (utf8_path == NULL) {
        return NULL;
    }
    const char *utf8_staging = (*env)->GetStringUTFChars(env, staging, NULL);
    if (utf8_staging == NULL) {
        (*env)->ReleaseStringUTFChars(env, path, utf8_path);
        return NULL;
    }
    AxioraBuffer buffer = axiora_package_load_to_directory_json(utf8_path, utf8_staging);
    (*env)->ReleaseStringUTFChars(env, staging, utf8_staging);
    (*env)->ReleaseStringUTFChars(env, path, utf8_path);
    return copy_result(env, buffer);
}

JNIEXPORT jbyteArray JNICALL
Java_mg_abela_axiora_app_RustPackageArchiveReader_nativeInspect(
    JNIEnv *env, jobject instance, jstring path) {
    (void)instance;
    return invoke(env, path, axiora_package_inspect_json);
}

JNIEXPORT jbyteArray JNICALL
Java_mg_abela_axiora_app_RustPackageArchiveReader_nativeLoad(
    JNIEnv *env, jobject instance, jstring path, jstring staging) {
    (void)instance;
    return invoke_load(env, path, staging);
}
