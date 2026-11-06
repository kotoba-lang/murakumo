/* C twin of gemm_metal.kotoba.in: C[M,N] = A[M,K] . B[K,N], A and B holding small
 * integers (the LCG draw & 127, minus 64: -64..63), accumulated exactly. Every
 * product is at most 4096 and K*4096 < 2^24, so each partial sum is an integer that
 * float32 holds exactly: the result is independent of the order the GPU's matrix
 * units add in, and the twin may sum in int64.
 * The checksum folds the float32 bit pattern of each C element in row-major order.
 *   cc -O2 -DM=512 -DK=512 -DN=640 gemm.c -o gemm */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#ifndef M
#define M 512
#endif
#ifndef K
#define K 512
#endif
#ifndef N
#define N 640
#endif
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static int16_t a_[M*K], b_[K*N];
int main(void){
  int64_t s=12345;
  for(int i=0;i<M*K;i++){s=lcg(s);a_[i]=(int16_t)(((s>>8)&127)-64);}
  for(int i=0;i<K*N;i++){s=lcg(s);b_[i]=(int16_t)(((s>>8)&127)-64);}
  int64_t c=s&281474976710655LL;
  for(int i=0;i<M;i++) for(int j=0;j<N;j++){
    int64_t acc=0; for(int k=0;k<K;k++) acc+=(int64_t)a_[i*K+k]*b_[k*N+j];
    float f=(float)acc; uint32_t u; memcpy(&u,&f,4);
    c=(c*31+(int64_t)u)&281474976710655LL;
  }
  printf("%lld\n",(long long)c); return 0;
}
