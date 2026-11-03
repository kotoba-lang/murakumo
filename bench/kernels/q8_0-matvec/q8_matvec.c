/* C twin of q8_matvec.kotoba.in: same LCG, packing, block layout and
 * checksum, so both print the same number. Build with -DROWS=, -DCOLS=,
 * -DREPS= (run.sh does). Prints the checksum like `amu run` does. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#ifndef ROWS
#define ROWS 4864
#endif
#ifndef COLS
#define COLS 896
#endif
#ifndef REPS
#define REPS 1
#endif
#define BLOCKS (COLS / 32)
#define MASK48 281474976710655LL

static int64_t lcg(int64_t s) { return (s * 1103515245 + 12345) & 2147483647; }

static int64_t sbyte(int64_t w, int shl) {
    return (int64_t)((uint64_t)w << shl) >> 56;
}

static int64_t word_dot(int64_t w, const int64_t *x) {
    return sbyte(w, 56) * x[0] + sbyte(w, 48) * x[1] + sbyte(w, 40) * x[2] +
           sbyte(w, 32) * x[3] + sbyte(w, 24) * x[4] + sbyte(w, 16) * x[5] +
           sbyte(w, 8) * x[6] + (w >> 56) * x[7];
}

static int64_t matvec(const int64_t *W, const int64_t *SW, const int64_t *x,
                      const int64_t *SX, int64_t c) {
    for (int64_t r = 0; r < ROWS; r++) {
        int64_t acc = 0;
        for (int64_t k = 0; k < BLOCKS; k++) {
            const int64_t *w = W + (r * BLOCKS + k) * 4;
            const int64_t *xb = x + k * 32;
            int64_t sumi = word_dot(w[0], xb) + word_dot(w[1], xb + 8) +
                           word_dot(w[2], xb + 16) + word_dot(w[3], xb + 24);
            acc += sumi * SW[r * BLOCKS + k] * SX[k];
        }
        c = (c * 31 + acc) & MASK48;
    }
    return c;
}

int main(void) {
    int64_t nw = (int64_t)ROWS * COLS / 8;
    int64_t *W = calloc(nw, 8), *SW = calloc(ROWS * BLOCKS, 8);
    int64_t *x = calloc(COLS, 8), *SX = calloc(BLOCKS, 8);
    int64_t s = 12345;
    for (int64_t i = 0; i < nw; i++) {
        uint64_t w = 0;
        for (int j = 0; j < 8; j++) {
            s = lcg(s);
            w |= (uint64_t)((s >> 16) & 255) << (8 * j);
        }
        W[i] = (int64_t)w;
    }
    for (int64_t i = 0; i < ROWS * BLOCKS; i++) { s = lcg(s); SW[i] = 1 + ((s >> 8) & 1023); }
    for (int64_t i = 0; i < COLS; i++) { s = lcg(s); x[i] = ((s >> 16) & 255) - 128; }
    for (int64_t i = 0; i < BLOCKS; i++) { s = lcg(s); SX[i] = 1 + ((s >> 8) & 1023); }
    int64_t c = s & MASK48;
    for (int i = 0; i < REPS; i++) c = matvec(W, SW, x, SX, c);
    printf("%lld\n", (long long)c);
    return 0;
}
