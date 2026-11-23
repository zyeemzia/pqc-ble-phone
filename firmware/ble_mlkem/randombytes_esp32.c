/* PQClean randombytes() for the ESP32 (hardware RNG).
 * randombytes.h maps randombytes -> PQCLEAN_randombytes, so this defines the
 * symbol PQClean's kem.c links against. */
#include "randombytes.h"
#include "esp_random.h"

int randombytes(uint8_t *out, size_t n) {
    esp_fill_random(out, n);
    return 0;
}
