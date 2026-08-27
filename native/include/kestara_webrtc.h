#ifndef KESTARA_WEBRTC_H
#define KESTARA_WEBRTC_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct kestara_bytes {
    uint8_t *data;
    size_t length;
} kestara_bytes;

int32_t kestara_abi_version(void);
kestara_bytes kestara_library_version(void);
int32_t kestara_runtime_create(
    const uint8_t *configuration,
    size_t configuration_length,
    uint64_t *runtime_out,
    kestara_bytes *error_out);
int32_t kestara_runtime_certificate_fingerprint(
    uint64_t runtime,
    kestara_bytes *value_out,
    kestara_bytes *error_out);
int32_t kestara_runtime_certificate_pem(
    uint64_t runtime,
    kestara_bytes *value_out,
    kestara_bytes *error_out);
int32_t kestara_runtime_submit(
    uint64_t runtime,
    uint64_t operation,
    int32_t command,
    uint64_t target,
    int64_t timeout_millis,
    const uint8_t *text,
    size_t text_length,
    const uint8_t *secondary_text,
    size_t secondary_text_length,
    int64_t number,
    int64_t secondary_number,
    const uint8_t *data,
    size_t data_length,
    const uint8_t *configuration,
    size_t configuration_length,
    kestara_bytes *error_out);
int32_t kestara_runtime_poll(
    uint64_t runtime,
    int64_t timeout_millis,
    kestara_bytes *event_out,
    kestara_bytes *error_out);
int32_t kestara_runtime_wake(uint64_t runtime, kestara_bytes *error_out);
int32_t kestara_runtime_release(uint64_t runtime, kestara_bytes *error_out);
void kestara_bytes_free(kestara_bytes bytes);

#ifdef __cplusplus
}
#endif

#endif
