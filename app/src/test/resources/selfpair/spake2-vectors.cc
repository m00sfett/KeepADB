// Prints SPAKE2 reference vectors from BoringSSL for KeepADB #962.
#include <stdio.h>
#include <string.h>
#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/evp.h>
#include "crypto/curve25519/internal.h"

static void hex(const char *name, const uint8_t *d, size_t n) {
  printf("%s=", name);
  for (size_t i = 0; i < n; i++) printf("%02x", d[i]);
  printf("\n");
}

int main() {
  static const uint8_t kClient[] = "adb pair client";
  static const uint8_t kServer[] = "adb pair server";
  int seen = 0, count = 0;
  for (int i = 0; seen != 0xff && i < 10000; i++) {
    uint8_t pw[6 + 64];
    snprintf((char *)pw, 7, "%06d", (i * 7919 + 123456) % 1000000);
    for (int j = 0; j < 64; j++) pw[6 + j] = (uint8_t)(i * 31 + j * 17 + 5);
    SPAKE2_CTX *a = SPAKE2_CTX_new(spake2_role_alice, kClient, sizeof(kClient), kServer, sizeof(kServer));
    SPAKE2_CTX *b = SPAKE2_CTX_new(spake2_role_bob, kServer, sizeof(kServer), kClient, sizeof(kClient));
    uint8_t am[32], bm[32], ak[64], bk[64]; size_t l;
    if (!SPAKE2_generate_msg(a, am, &l, 32, pw, sizeof(pw))) return 1;
    if (!SPAKE2_generate_msg(b, bm, &l, 32, pw, sizeof(pw))) return 1;
    // Low three bits of the reduced password hash before the "hack": recover from scalar mod 8
    // is impossible afterwards, so classify by how many multiples of l were added.
    uint8_t reduced[64]; memcpy(reduced, a->password_hash, 64); bssl::x25519_sc_reduce(reduced);
    int low = reduced[0] & 7;
    if (seen & (1 << low)) { SPAKE2_CTX_free(a); SPAKE2_CTX_free(b); continue; }
    seen |= 1 << low;
    uint8_t priv[32]; memcpy(priv, a->private_key, 32);
    if (!SPAKE2_process_msg(a, ak, &l, 64, bm, 32) || l != 64) return 1;
    if (!SPAKE2_process_msg(b, bk, &l, 64, am, 32)) return 1;
    if (memcmp(ak, bk, 64)) return 2;
    uint8_t aes[16];
    static const uint8_t info[] = "adb pairing_auth aes-128-gcm key";
    if (!HKDF(aes, 16, EVP_sha256(), ak, 64, nullptr, 0, info, sizeof(info) - 1)) return 3;
    printf("# vector %d, low bits of reduced password hash: %d\n", count++, low);
    hex("password", pw, sizeof(pw));
    hex("alice_private", priv, 32);
    hex("alice_msg", am, 32);
    hex("bob_msg", bm, 32);
    hex("key", ak, 64);
    hex("aes_key", aes, 16);
    SPAKE2_CTX_free(a); SPAKE2_CTX_free(b);
  }
  return seen == 0xff ? 0 : 4;
}
