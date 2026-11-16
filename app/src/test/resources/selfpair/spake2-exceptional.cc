// Prints SPAKE2 reference results for exceptional peer messages, from BoringSSL, for KeepADB #962.
#include <stdio.h>
#include <string.h>
#include <openssl/curve25519.h>
#include "crypto/curve25519/internal.h"

static void hex(const char *name, const uint8_t *d, size_t n) {
  printf("%s=", name);
  for (size_t i = 0; i < n; i++) printf("%02x", d[i]);
  printf("\n");
}
static int unhex(const char *s, uint8_t *out) {
  for (int i = 0; i < 32; i++) { unsigned v; if (sscanf(s + 2 * i, "%2x", &v) != 1) return 0; out[i] = (uint8_t)v; }
  return 1;
}

int main() {
  static const uint8_t kClient[] = "adb pair client";
  static const uint8_t kServer[] = "adb pair server";
  // Encodings: identity, order 2, order 4 (two), order 8 (four), then non-canonical y = p + k
  // for k = 0..18 with both sign bits, and a few that are not on the curve.
  static const char *kFixed[] = {
    "0100000000000000000000000000000000000000000000000000000000000000",
    "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000080",
    "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
    "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85",
    "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
    "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
    "0100000000000000000000000000000000000000000000000000000000000080",
    "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    "0200000000000000000000000000000000000000000000000000000000000000",
    "5866666666666666666666666666666666666666666666666666666666666666",
  };
  char buf[65];
  int count = 0;
  for (int i = 0; i < 12 + 19 * 2; i++) {
    uint8_t peer[32];
    if (i < 12) {
      unhex(kFixed[i], peer);
    } else {
      int k = (i - 12) / 2, sign = (i - 12) % 2;
      memset(peer, 0xff, 32);
      peer[0] = (uint8_t)(0xed + k);  // p = 2^255 - 19 ends in 0xed
      peer[31] = sign ? 0xff : 0x7f;
    }
    uint8_t pw[6 + 64];
    snprintf((char *)pw, 7, "%06d", (i * 104729 + 271828) % 1000000);
    for (int j = 0; j < 64; j++) pw[6 + j] = (uint8_t)(i * 13 + j * 29 + 7);
    SPAKE2_CTX *a = SPAKE2_CTX_new(spake2_role_alice, kClient, sizeof(kClient), kServer, sizeof(kServer));
    uint8_t am[32], ak[64]; size_t l;
    if (!SPAKE2_generate_msg(a, am, &l, 32, pw, sizeof(pw))) return 1;
    uint8_t priv[32]; memcpy(priv, a->private_key, 32);
    int ok = SPAKE2_process_msg(a, ak, &l, 64, peer, 32);
    printf("# vector %d\n", count++);
    hex("password", pw, sizeof(pw));
    hex("alice_private", priv, 32);
    hex("alice_msg", am, 32);
    hex("peer_msg", peer, 32);
    if (ok) hex("key", ak, 64); else printf("key=reject\n");
    SPAKE2_CTX_free(a);
    (void)buf;
  }
  return 0;
}
