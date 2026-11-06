/* C twin of kernel.kotoba.in: linear layer C = A.B + bias in f32 (the Q/K/V,
 * output and feed-forward projections). A is [M,K], B is [K,N] row-major, bias
 * is [N]. The accumulator starts at the bias and adds one product per k in
 * order, each multiply and add rounded alone.
 *   cc -O2 -ffp-contract=off -I.. -DM=64 -DK=320 -DN=320 -DREPS=1 */
#include "../kern.h"
#ifndef M
#define M 64
#endif
#ifndef K
#define K 320
#endif
#ifndef N
#define N 320
#endif
#ifndef REPS
#define REPS 1
#endif
static float a_[M*K], b_[K*N], bias_[N];
static int64_t fill(void){
  int64_t s=12345;
  s=fill16(a_,M*K,s,0x39000000u,0); s=fill16(b_,K*N,s,0x37800000u,0); s=fill16(bias_,N,s,0x38800000u,0);
  return s;
}
static float at(int i,int j){
  float acc=bias_[j];
  for(int k=0;k<K;k++){ float p=a_[i*K+k]*b_[k*N+j]; acc=acc+p; }
  return acc;
}
#ifndef NO_MAIN
int main(void){
  int64_t c=fill()&MASK48;
  for(int rep=0;rep<REPS;rep++) for(int i=0;i<M;i++) for(int j=0;j<N;j++)
    c=(c*31+(int64_t)B(at(i,j)))&MASK48;
  printf("%lld\n",(long long)c); return 0;
}
#endif
